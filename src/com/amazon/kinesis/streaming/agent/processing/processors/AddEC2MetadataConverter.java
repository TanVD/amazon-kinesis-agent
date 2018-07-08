/*
 * Copyright 2014-2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file.
 * This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.amazon.kinesis.streaming.agent.processing.processors;

import com.amazon.kinesis.streaming.agent.ByteBuffers;
import com.amazon.kinesis.streaming.agent.Logging;
import com.amazon.kinesis.streaming.agent.config.Configuration;
import com.amazon.kinesis.streaming.agent.processing.exceptions.DataConversionException;
import com.amazon.kinesis.streaming.agent.processing.interfaces.IDataConverter;
import com.amazon.kinesis.streaming.agent.processing.interfaces.IJSONPrinter;
import com.amazon.kinesis.streaming.agent.processing.utils.ProcessingUtilsFactory;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeTagsRequest;
import com.amazonaws.services.ec2.model.DescribeTagsResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.TagDescription;
import com.amazonaws.util.EC2MetadataUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parse the log entries from log file, and convert the log entries into JSON.
 * <p>
 * Configuration of this converter looks like:
 * {
 * "optionName": "ADDEC2METADATA",
 * "logFormat": "RFC3339SYSLOG"
 * }
 *
 * @author buholzer
 */
public class AddEC2MetadataConverter implements IDataConverter {

    private static final Logger LOGGER = Logging.getLogger(AddEC2MetadataConverter.class);
    private IJSONPrinter jsonProducer;
    private Map<String, Object> metadata;
    private long metadataTimestamp;
    private long metadataTTL = 1000 * 60 * 60; // Update metadata every hour
    private Configuration config;

    public AddEC2MetadataConverter(Configuration config) {
        this.config = config;
        jsonProducer = ProcessingUtilsFactory.getPrinter(config);

        if (config.containsKey("metadataTTL")) {
            try {
                metadataTTL = config.readInteger("metadataTTL") * 1000;
                LOGGER.info("Setting metadata TTL to " + metadataTTL + " millis");
            } catch (Exception ex) {
                LOGGER.warn("Error converting metadataTTL, ignoring");
            }
        }

        refreshEC2Metadata();
    }

    @Override
    public ByteBuffer convert(ByteBuffer data) throws DataConversionException {

        if ((metadataTimestamp + metadataTTL) < System.currentTimeMillis()) refreshEC2Metadata();

        if (metadata == null || metadata.isEmpty()) {
            LOGGER.warn("Unable to append metadata, no metadata found");
            return data;
        }

        String dataStr = ByteBuffers.toString(data, StandardCharsets.UTF_8);

        ObjectMapper mapper = new ObjectMapper();
        TypeReference<LinkedHashMap<String, Object>> typeRef =
                new TypeReference<LinkedHashMap<String, Object>>() {
                };

        LinkedHashMap<String, Object> dataObj;
        try {
            dataObj = mapper.readValue(dataStr, typeRef);
        } catch (Exception ex) {
            throw new DataConversionException("Error converting json source data to map", ex);
        }

        // Appending EC2 metadata
        dataObj.putAll(metadata);

        String dataJson = jsonProducer.writeAsString(dataObj) + NEW_LINE;
        return ByteBuffer.wrap(dataJson.getBytes(StandardCharsets.UTF_8));
    }

    private void refreshEC2Metadata() {
        LOGGER.info("Refreshing EC2 metadata");

        metadataTimestamp = System.currentTimeMillis();

        try {
            EC2MetadataUtils.InstanceInfo info = EC2MetadataUtils.getInstanceInfo();

            metadata = new LinkedHashMap<>();

            if (config.containsKey("privateIp")) {
                metadata.put(config.readString("privateIp"), info.getPrivateIp());
            }

            if (config.containsKey("availabilityZone")) {
                metadata.put(config.readString("availabilityZone"), info.getAvailabilityZone());
            }

            if (config.containsKey("instanceId")) {
                metadata.put(config.readString("instanceId"), info.getInstanceId());
            }

            if (config.containsKey("instanceType")) {
                metadata.put(config.readString("instanceType"), info.getInstanceType());
            }


            if (config.containsKey("accountId")) {
                metadata.put(config.readString("accountId"), info.getAccountId());
            }

            if (config.containsKey("amiId")) {
                metadata.put(config.readString("amiId"), info.getImageId());
            }

            if (config.containsKey("region")) {
                metadata.put(config.readString("region"), info.getRegion());
            }

            if (config.containsKey("metadataTimestamp")) {
                metadata.put(config.readString("metadataTimestamp"),
                        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
                                .format(new Date(metadataTimestamp)));
            }

            if (config.containsKey("tags")) {
                Configuration tagsConfig = config.readConfiguration("tags");
                final AmazonEC2 ec2 = AmazonEC2ClientBuilder.defaultClient();
                DescribeTagsResult result = ec2.describeTags(
                        new DescribeTagsRequest().withFilters(
                                new Filter().withName("resource-id").withValues(info.getInstanceId())));
                List<TagDescription> tags = result.getTags();

                Map<String, Object> metadataTags = new LinkedHashMap<String, Object>();
                for (TagDescription tag : tags) {
                    metadataTags.put(tag.getKey().toLowerCase(), tag.getValue());
                }

                for (Map.Entry<String, Object> value : tagsConfig.getConfigMap().entrySet()) {
                    Object tagValue = metadataTags.get(value.getKey());
                    if (metadataTags.get(value.getKey()) != null) {
                        metadata.put((String) (value.getValue()), tagValue);
                    }
                }
            }


        } catch (Exception ex) {
            LOGGER.warn("Error while updating EC2 metadata - " + ex.getMessage() + ", ignoring");
        }
    }
}
