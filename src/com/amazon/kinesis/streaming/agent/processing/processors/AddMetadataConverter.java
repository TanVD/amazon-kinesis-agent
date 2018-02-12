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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;

import com.amazon.kinesis.streaming.agent.ByteBuffers;
import com.amazon.kinesis.streaming.agent.config.Configuration;
import com.amazon.kinesis.streaming.agent.processing.exceptions.DataConversionException;
import com.amazon.kinesis.streaming.agent.processing.interfaces.IDataConverter;
import com.amazon.kinesis.streaming.agent.processing.interfaces.IJSONPrinter;
import com.amazon.kinesis.streaming.agent.processing.utils.ProcessingUtilsFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.joda.time.DateTime;

/**
 * Build record as JSON object with a "metadata" key for arbitrary KV pairs
 *   and "message" key with the raw data
 *
 * Configuration looks like:
 *
 * { 
 *   "optionName": "ADDMETADATA",
 *   "metadata": {
 *     "key": "value",
 *     "foo": {
 *       "bar": "baz"
 *     }
 *   }
 * }
 *
 * @author zacharya
 *
 */
public class AddMetadataConverter implements IDataConverter {

    enum MetadataFunctions {
        Time {
            @Override
            public String invoke() {
                return Long.toString(DateTime.now().getMillis());
            }
        };

        public abstract String invoke();
    }

    private Map<String, Object> metadata;
    private Map<String, String> functions;
    private final IJSONPrinter jsonProducer;

    public AddMetadataConverter(Configuration config) {
      metadata = (Map<String, Object>) config.getConfigMap().get("metadata");
      functions = (Map<String, String>) config.getConfigMap().get("functions");
      jsonProducer = ProcessingUtilsFactory.getPrinter(config);
    }

    @Override
    public ByteBuffer convert(ByteBuffer data) throws DataConversionException {

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

        dataObj.putAll(metadata);

        if (functions != null) {
            for (Map.Entry<String, String> function : functions.entrySet()) {
                dataObj.put(function.getValue(), MetadataFunctions.valueOf(function.getKey()).invoke());
            }
        }

        String dataJson = jsonProducer.writeAsString(dataObj) + NEW_LINE;
        return ByteBuffer.wrap(dataJson.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}