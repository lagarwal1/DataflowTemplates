/*
 * Copyright (C) 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.v2.transforms;

import static com.google.common.truth.Truth.assertThat;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.Field.Mode;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.teleport.v2.coders.FailsafeElementCoder;
import com.google.cloud.teleport.v2.transforms.BigQueryConverters.BigQueryTableConfigManager;
import com.google.cloud.teleport.v2.transforms.BigQueryConverters.FailsafeJsonToTableRow;
import com.google.cloud.teleport.v2.transforms.BigQueryConverters.SchemaUtils;
import com.google.cloud.teleport.v2.transforms.BigQueryConverters.TableRowToGenericRecordFn;
import com.google.cloud.teleport.v2.values.FailsafeElement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.extensions.avro.schemas.utils.AvroUtils;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryUtils;
import org.apache.beam.sdk.io.gcp.bigquery.TableRowJsonCoder;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessageWithAttributesCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.NeedsRunner;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Strings;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BigQueryConverters}. */
@RunWith(JUnit4.class)
public class BigQueryConvertersTest {

  static final TableRow ROW =
      new TableRow().set("id", "007").set("state", "CA").set("price", 26.23);

  /** The tag for the main output of the json transformation. */
  static final TupleTag<FailsafeElement<TableRow, String>> TRANSFORM_OUT = new TupleTag<>() {};

  /** The tag for the dead-letter output of the json to table row transform. */
  static final TupleTag<FailsafeElement<TableRow, String>> TRANSFORM_DEADLETTER_OUT =
      new TupleTag<>() {};

  /** The tag for the main output of the json transformation. */
  static final TupleTag<FailsafeElement<TableRow, String>> UDF_OUT = new TupleTag<>() {};

  /** The tag for the dead-letter output of the json to table row transform. */
  static final TupleTag<FailsafeElement<TableRow, String>> UDF_TRANSFORM_DEADLETTER_OUT =
      new TupleTag<>() {};

  /** String/String Coder for FailsafeElement. */
  static final FailsafeElementCoder<String, String> FAILSAFE_ELEMENT_CODER =
      FailsafeElementCoder.of(
          NullableCoder.of(StringUtf8Coder.of()), NullableCoder.of(StringUtf8Coder.of()));

  /** TableRow/String Coder for FailsafeElement. */
  static final FailsafeElementCoder<TableRow, String> FAILSAFE_TABLE_ROW_ELEMENT_CODER =
      FailsafeElementCoder.of(TableRowJsonCoder.of(), NullableCoder.of(StringUtf8Coder.of()));

  // Define the TupleTag's here otherwise the anonymous class will force the test method to
  // be serialized.
  private static final TupleTag<TableRow> TABLE_ROW_TAG = new TupleTag<>() {};
  private static final TupleTag<FailsafeElement<PubsubMessage, String>> FAILSAFE_ELM_TAG =
      new TupleTag<>() {};
  private static final String jsonifiedTableRow =
      "{\"id\":\"007\",\"state\":\"CA\",\"price\":26.23}";
  private static final String udfOutputRow =
      "{\"id\":\"007\",\"state\":\"CA\",\"price\":26.23,\"someProp\":\"someValue\"}";
  @Rule public final transient TestPipeline pipeline = TestPipeline.create();
  @Rule public ExpectedException expectedException = ExpectedException.none();
  private static final String AVRO_SCHEMA_TEMPLATE =
      new StringBuilder()
          .append("{")
          .append(" \"type\" : \"record\",")
          .append(" \"name\" : \"BigQueryTestData\",")
          .append(" \"namespace\" : \"\",")
          .append(" \"fields\" :")
          .append("  [%s],")
          .append(" \"doc:\" : \"A basic Avro schema for unit testing purposes\"")
          .append("}")
          .toString();
  private String avroFieldTemplate =
      new StringBuilder()
          .append("{")
          .append(" \"name\" : \"%s\",")
          .append(" \"type\" : \"%s\",")
          .append(" \"doc\"  : \"%s\"")
          .append("}")
          .toString();
  private final String idField = "id";
  private final String idFieldDesc = "Unique identifier";
  private final int idFieldValueInt = 87234;
  private final String idFieldValueStr = "87234";
  private final String nullField = "comment";
  private final String nullFieldDesc = "Comment";
  private final String shortStringFieldDesc = "Author name";
  private final String shortStringFieldValue = "Morgan le Fay";
  private final String longStringField = "excerpt";
  private final String longStringFieldDesc = "Excerpt from the article";
  private final String longStringFieldValue = Strings.repeat("dignissimos", 5000);
  private final String integerField = "year";
  private final String integerFieldDesc = "Publication year";
  private final long integerFieldValue = 2013L;
  private final String int64Field = "year_64";
  private final String int64FieldDesc = "Publication year (64)";
  private final long int64FieldValue = 2015L;
  private final String floatField = "price";
  private final String floatFieldDesc = "Price";
  private final double floatFieldValue = 47.89;
  private final String float64Field = "price_64";
  private final String float64FieldDesc = "Price (64)";
  private final double float64FieldValue = 173.45;
  private final String booleanField = "available";
  private final String booleanFieldDesc = "Available?";
  private final boolean booleanFieldValue = true;
  private final String boolField = "borrowable";
  private final String boolFieldDesc = "Can be borrowed?";
  private final boolean boolFieldValue = false;
  private final String validTimestampField = "date_ts";
  private final String validTimestampFieldDesc = "Publication date (ts)";
  private final long validTimestampFieldValueMicros = 1376954900000567L;
  private final long validTimestampFieldValueMillis = 1376954900000L;
  private final String invalidTimestampField = "date_ts_invalid";
  private final String invalidTimestampFieldDesc = "Expiration date (ts)";
  private final long invalidTimestampFieldValueNanos = 1376954900000567000L;
  private final String dateField = "date";
  private final String dateFieldDesc = "Publication day";
  private final String dateFieldValue = "2013-08-19";
  private final String timeField = "time";
  private final String timeFieldDesc = "Publication time";
  private final String timeFieldValue = "23:28:20.000567";
  private final String dateTimeField = "full_date";
  private final String dateTimeFieldDesc = "Full publication date";
  private final String dateTimeFieldValue = "2013-08-19 23:28:20.000567";

  /** Tests the {@link BigQueryConverters.FailsafeJsonToTableRow} transform with good input. */
  @Test
  @Category(NeedsRunner.class)
  public void testFailsafeJsonToTableRowValidInput() {
    // Test input
    final String payload = "{\"ticker\": \"GOOGL\", \"price\": 1006.94}";
    final Map<String, String> attributes = ImmutableMap.of("id", "0xDb12", "type", "stock");
    final PubsubMessage message = new PubsubMessage(payload.getBytes(), attributes);

    final FailsafeElement<PubsubMessage, String> input = FailsafeElement.of(message, payload);

    // Expected Output
    TableRow expectedRow = new TableRow().set("ticker", "GOOGL").set("price", 1006.94);

    // Register the coder for the pipeline. This prevents having to invoke .setCoder() on
    // many transforms.
    FailsafeElementCoder<PubsubMessage, String> coder =
        FailsafeElementCoder.of(PubsubMessageWithAttributesCoder.of(), StringUtf8Coder.of());

    CoderRegistry coderRegistry = pipeline.getCoderRegistry();
    coderRegistry.registerCoderForType(coder.getEncodedTypeDescriptor(), coder);

    // Build the pipeline
    PCollectionTuple output =
        pipeline
            .apply("CreateInput", Create.of(input).withCoder(coder))
            .apply(
                "JsonToTableRow",
                FailsafeJsonToTableRow.<PubsubMessage>newBuilder()
                    .setSuccessTag(TABLE_ROW_TAG)
                    .setFailureTag(FAILSAFE_ELM_TAG)
                    .build());

    // Assert
    PAssert.that(output.get(TABLE_ROW_TAG)).containsInAnyOrder(expectedRow);
    PAssert.that(output.get(FAILSAFE_ELM_TAG)).empty();

    // Execute the test
    pipeline.run();
  }

  /**
   * Tests the {@link BigQueryConverters.FailsafeJsonToTableRow} transform with invalid JSON input.
   */
  @Test
  @Category(NeedsRunner.class)
  public void testFailsafeJsonToTableRowInvalidJSON() {
    // Test input
    final String payload = "{\"ticker\": \"GOOGL\", \"price\": 1006.94";
    final Map<String, String> attributes = ImmutableMap.of("id", "0xDb12", "type", "stock");
    final PubsubMessage message = new PubsubMessage(payload.getBytes(), attributes);

    final FailsafeElement<PubsubMessage, String> input = FailsafeElement.of(message, payload);

    // Register the coder for the pipeline. This prevents having to invoke .setCoder() on
    // many transforms.
    FailsafeElementCoder<PubsubMessage, String> coder =
        FailsafeElementCoder.of(PubsubMessageWithAttributesCoder.of(), StringUtf8Coder.of());

    CoderRegistry coderRegistry = pipeline.getCoderRegistry();
    coderRegistry.registerCoderForType(coder.getEncodedTypeDescriptor(), coder);

    // Build the pipeline
    PCollectionTuple output =
        pipeline
            .apply("CreateInput", Create.of(input).withCoder(coder))
            .apply(
                "JsonToTableRow",
                FailsafeJsonToTableRow.<PubsubMessage>newBuilder()
                    .setSuccessTag(TABLE_ROW_TAG)
                    .setFailureTag(FAILSAFE_ELM_TAG)
                    .build());

    // Assert
    PAssert.that(output.get(TABLE_ROW_TAG)).empty();
    PAssert.that(output.get(FAILSAFE_ELM_TAG))
        .satisfies(
            collection -> {
              final FailsafeElement<PubsubMessage, String> result = collection.iterator().next();
              // Check the individual elements of the PubsubMessage since the message above won't be
              // serializable.
              assertThat(new String(result.getOriginalPayload().getPayload())).isEqualTo(payload);
              assertThat(result.getOriginalPayload().getAttributeMap()).isEqualTo(attributes);
              assertThat(result.getPayload()).isEqualTo(payload);
              assertThat(result.getErrorMessage()).isNotNull();
              assertThat(result.getStacktrace()).isNotNull();
              return null;
            });

    // Execute the test
    pipeline.run();
  }

  /** Generates an Avro record with a single field. */
  private Record generateSingleFieldAvroRecord(
      String name, String type, String description, Object value) {
    Schema avroSchema =
        new Schema.Parser()
            .parse(
                String.format(
                    AVRO_SCHEMA_TEMPLATE,
                    new StringBuilder()
                        .append(String.format(avroFieldTemplate, name, type, description))
                        .toString()));
    GenericRecordBuilder builder = new GenericRecordBuilder(avroSchema);
    builder.set(name, value);
    return builder.build();
  }

  /** Generates a short string Avro field. */
  private String generateShortStringField() {
    String shortStringField = "author";
    return String.format(avroFieldTemplate, shortStringField, "string", shortStringFieldDesc);
  }

  /** Generates a long string Avro field. */
  private String generateLongStringField() {
    return String.format(avroFieldTemplate, longStringField, "string", longStringFieldDesc);
  }

  /** Generate a BigQuery TableSchema with nested fields. */
  private TableFieldSchema generateNestedTableFieldSchema() {
    return new TableFieldSchema()
        .setName("address")
        .setType("RECORD")
        .setFields(
            Arrays.asList(
                new TableFieldSchema().setName("street_number").setType("INTEGER"),
                new TableFieldSchema().setName("street_name").setType("STRING")));
  }

  /** Generates an Avro record with a record field type. */
  static Record generateNestedAvroRecord() {
    String avroRecordFieldSchema =
        new StringBuilder()
            .append("{")
            .append("  \"name\" : \"address\",")
            .append("  \"type\" :")
            .append("  {")
            .append("    \"type\" : \"record\",")
            .append("    \"name\" : \"address\",")
            .append("    \"namespace\"  : \"nothing\",")
            .append("    \"fields\" : ")
            .append("    [")
            .append("      {\"name\" : \"street_number\", \"type\" : \"int\"},")
            .append("      {\"name\" : \"street_name\", \"type\" : \"string\"}")
            .append("    ]")
            .append("  }")
            .append("}")
            .toString();
    Schema avroSchema =
        new Schema.Parser().parse(String.format(AVRO_SCHEMA_TEMPLATE, avroRecordFieldSchema));
    GenericRecordBuilder addressBuilder =
        new GenericRecordBuilder(avroSchema.getField("address").schema());
    addressBuilder.set("street_number", 12);
    addressBuilder.set("street_name", "Magnolia street");
    GenericRecordBuilder builder = new GenericRecordBuilder(avroSchema);
    builder.set("address", addressBuilder.build());
    return builder.build();
  }

  /**
   * Tests {@link BigQueryConverters.ReadBigQueryTableRows} throws exception when neither a query or
   * input table is provided.
   */
  @Test(expected = IllegalArgumentException.class)
  public void testReadBigQueryInvalidInput() {

    BigQueryConverters.BigQueryReadOptions options =
        PipelineOptionsFactory.create().as(BigQueryConverters.BigQueryReadOptions.class);

    options.setInputTableSpec(null);
    options.setQuery(null);

    pipeline.apply(
        BigQueryConverters.ReadBigQueryTableRows.newBuilder().setOptions(options).build());

    pipeline.run();
  }

  /** Tests {@link BigQueryConverters.BigQueryReadOptions} works with some options. */
  @Test
  public void testBigQueryReadOptions() {

    BigQueryConverters.BigQueryReadOptions options =
        PipelineOptionsFactory.create().as(BigQueryConverters.BigQueryReadOptions.class);

    options.setInputTableSpec(null);
    options.setQuery("select * from sampledb.sample_table");
    options.setQueryTempDataset("temp_dataset");

    assertThat(options.getQueryTempDataset()).isEqualTo("temp_dataset");
  }

  /**
   * Tests that {@link BigQueryConverters.TableRowToFailsafeJsonDocument} transform returns the
   * correct element.
   */
  @Test
  public void testTableRowToJsonDocument() {
    CoderRegistry coderRegistry = pipeline.getCoderRegistry();

    coderRegistry.registerCoderForType(
        FAILSAFE_ELEMENT_CODER.getEncodedTypeDescriptor(), FAILSAFE_ELEMENT_CODER);

    coderRegistry.registerCoderForType(
        FAILSAFE_TABLE_ROW_ELEMENT_CODER.getEncodedTypeDescriptor(),
        FAILSAFE_TABLE_ROW_ELEMENT_CODER);

    BigQueryConverters.BigQueryReadOptions options =
        PipelineOptionsFactory.create().as(BigQueryConverters.BigQueryReadOptions.class);

    options.setInputTableSpec(null);
    options.setQuery(null);

    PCollectionTuple testTuple =
        pipeline
            .apply("Create Input", Create.<TableRow>of(ROW).withCoder(TableRowJsonCoder.of()))
            .apply(
                "TestRowToDocument",
                BigQueryConverters.TableRowToFailsafeJsonDocument.newBuilder()
                    .setTransformDeadletterOutTag(TRANSFORM_DEADLETTER_OUT)
                    .setTransformOutTag(TRANSFORM_OUT)
                    .setUdfDeadletterOutTag(UDF_TRANSFORM_DEADLETTER_OUT)
                    .setUdfOutTag(UDF_OUT)
                    .setOptions(
                        options.as(
                            JavascriptTextTransformer.JavascriptTextTransformerOptions.class))
                    .build());

    // Assert
    PAssert.that(testTuple.get(TRANSFORM_OUT))
        .satisfies(
            collection -> {
              FailsafeElement<TableRow, String> element = collection.iterator().next();
              assertThat(element.getOriginalPayload()).isEqualTo(ROW);
              assertThat(element.getPayload()).isEqualTo(jsonifiedTableRow);
              return null;
            });

    // Execute pipeline
    pipeline.run();
  }

  /**
   * Tests that {@link BigQueryConverters.BigQueryTableConfigManager} returns expected table names
   * when supplying templated values.
   */
  @Test
  public void serializableFunctionConvertsTableRowToGenericRecordUsingSchema() {
    GenericRecord expectedRecord = generateNestedAvroRecord();
    Row testRow =
        AvroUtils.toBeamRowStrict(
            expectedRecord, AvroUtils.toBeamSchema(expectedRecord.getSchema()));
    TableRow inputRow = BigQueryUtils.toTableRow(testRow);
    TableRowToGenericRecordFn rowToGenericRecordFn =
        TableRowToGenericRecordFn.of(expectedRecord.getSchema());

    GenericRecord actualRecord = rowToGenericRecordFn.apply(inputRow);

    assertThat(actualRecord).isEqualTo(expectedRecord);
  }

  @Test
  public void testBigQueryTableConfigManagerTemplates() {
    String projectIdVal = "my_project";
    String datasetTemplateVal = "my_dataset";
    String tableTemplateVal = "my_table";
    String outputTableSpec = "";

    BigQueryTableConfigManager mgr =
        new BigQueryTableConfigManager(
            projectIdVal, datasetTemplateVal,
            tableTemplateVal, outputTableSpec);

    String outputTableSpecResult = "my_project:my_dataset.my_table";
    assertThat(mgr.getOutputTableSpec()).isEqualTo(outputTableSpecResult);
  }

  /**
   * Tests that {@link BigQueryConverters.BigQueryTableConfigManager} returns expected table names
   * when supplying full table path.
   */
  @Test
  public void testBigQueryTableConfigManagerTableSpec() {
    String projectIdVal = null;
    String datasetTemplateVal = null;
    String tableTemplateVal = null;
    String outputTableSpec = "my_project:my_dataset.my_table";

    BigQueryTableConfigManager mgr =
        new BigQueryTableConfigManager(
            projectIdVal, datasetTemplateVal,
            tableTemplateVal, outputTableSpec);

    assertThat(mgr.getDatasetTemplate()).isEqualTo("my_dataset");
    assertThat(mgr.getTableTemplate()).isEqualTo("my_table");
  }

  /**
   * Tests that {@link BigQueryConverters.SchemaUtils} properly cleans and returns a BigQuery Schema
   * from a JSON string.
   */
  @Test
  public void testSchemaUtils() {
    String jsonSchemaStr = "[{\"type\":\"STRING\",\"name\":\"column\",\"mode\":\"NULLABLE\"}]";
    List<Field> fields = SchemaUtils.schemaFromString(jsonSchemaStr);

    assertThat(fields.get(0).getName()).isEqualTo("column");
    assertThat(fields.get(0).getMode()).isEqualTo(Mode.NULLABLE);
    assertThat(fields.get(0).getType()).isEqualTo(LegacySQLTypeName.STRING);
  }

  @Test
  public void testSanitizeBigQueryChars() {
    String sourceName = "my$table.name";

    String name = BigQueryConverters.sanitizeBigQueryChars(sourceName, "_");

    assertThat(name).isEqualTo("my_table_name");
  }

  @Test
  public void testSanitizeBigQueryDatasetChars() {
    String sourceName = "my$data-set.name";

    String name = BigQueryConverters.sanitizeBigQueryDatasetChars(sourceName, "_");

    assertThat(name).isEqualTo("my_data_set_name");
  }
}
