/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.it.gcp.spanner;

import static com.google.cloud.spanner.Value.int64;
import static com.google.cloud.spanner.Value.string;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.Timestamp;
import com.google.cloud.spanner.Database;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.Instance;
import com.google.cloud.spanner.InstanceAdminClient;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerExceptionFactory;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;
import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.apache.beam.it.gcp.monitoring.MonitoringClient;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link SpannerResourceManager}. */
@RunWith(JUnit4.class)
public final class SpannerResourceManagerTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Spanner spanner;

  @Mock private Database database;
  @Mock private Instance instance;
  @Mock private InstanceAdminClient instanceAdminClient;
  @Mock private DatabaseAdminClient databaseAdminClient;
  @Mock private ResultSet resultSet;
  @Mock private MonitoringClient monitoringClient;

  private static final String TEST_ID = "test";
  private static final String PROJECT_ID = "test-project";
  private static final String REGION = "us-east1";
  private static final Dialect DIALECT = Dialect.GOOGLE_STANDARD_SQL;
  private SpannerResourceManager testManager;

  @Captor private ArgumentCaptor<Iterable<Mutation>> writeMutationCaptor;
  @Captor private ArgumentCaptor<Iterable<String>> statementCaptor;
  @Captor private ArgumentCaptor<String> instanceIdCaptor;
  @Captor private ArgumentCaptor<String> databaseIdCaptor;
  @Captor private ArgumentCaptor<String> projectIdCaptor;

  @Before
  public void setUp() {
    testManager =
        new SpannerResourceManager(
            SpannerResourceManager.builder(TEST_ID, PROJECT_ID, REGION, DIALECT), spanner);
  }

  private void prepareCreateInstanceMock() throws ExecutionException, InterruptedException {
    when(spanner.getInstanceAdminClient().createInstance(any()).get()).thenReturn(instance);
  }

  @Test
  public void testExecuteDdlStatementShouldThrowExceptionWhenSpannerCreateInstanceFails()
      throws ExecutionException, InterruptedException {
    // arrange
    when(spanner.getInstanceAdminClient().createInstance(any()).get())
        .thenThrow(InterruptedException.class);
    prepareCreateDatabaseMock();
    prepareUpdateDatabaseMock();
    String statement =
        "CREATE TABLE Singers (\n"
            + "  SingerId   INT64 NOT NULL,\n"
            + "  FirstName  STRING(1024),\n"
            + "  LastName   STRING(1024),\n"
            + ") PRIMARY KEY (SingerId)";

    // act & assert
    assertThrows(
        SpannerResourceManagerException.class, () -> testManager.executeDdlStatement(statement));
  }

  @Test
  public void testExecuteDdlStatementShouldThrowExceptionWhenSpannerCreateDatabaseFails()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareCreateInstanceMock();
    when(spanner.getDatabaseAdminClient().createDatabase(any(), any()).get())
        .thenThrow(InterruptedException.class);
    prepareUpdateDatabaseMock();
    String statement =
        "CREATE TABLE Singers (\n"
            + "  SingerId   INT64 NOT NULL,\n"
            + "  FirstName  STRING(1024),\n"
            + "  LastName   STRING(1024),\n"
            + ") PRIMARY KEY (SingerId)";

    // act & assert
    assertThrows(
        SpannerResourceManagerException.class, () -> testManager.executeDdlStatement(statement));
  }

  @Test
  public void testExecuteDdlStatementShouldThrowExceptionWhenSpannerUpdateDatabaseFails()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareCreateInstanceMock();
    prepareCreateDatabaseMock();
    when(spanner.getDatabaseAdminClient().updateDatabaseDdl(any(), any(), any(), any()).get())
        .thenThrow(InterruptedException.class);
    String statement =
        "CREATE TABLE Singers (\n"
            + "  SingerId   INT64 NOT NULL,\n"
            + "  FirstName  STRING(1024),\n"
            + "  LastName   STRING(1024),\n"
            + ") PRIMARY KEY (SingerId)";

    // act & assert
    assertThrows(
        SpannerResourceManagerException.class, () -> testManager.executeDdlStatement(statement));
  }

  @Test
  public void testExecuteDdlStatementShouldWorkWhenSpannerDoesntThrowAnyError()
      throws ExecutionException, InterruptedException {
    //   arrange
    prepareCreateInstanceMock();
    prepareCreateDatabaseMock();
    prepareUpdateDatabaseMock();
    String statement =
        "CREATE TABLE Singers (\n"
            + "  SingerId   INT64 NOT NULL,\n"
            + "  FirstName  STRING(1024),\n"
            + "  LastName   STRING(1024),\n"
            + ") PRIMARY KEY (SingerId)";

    // act
    testManager.executeDdlStatement(statement);

    // assert
    // verify createInstance, createDatabase, and updateDatabaseDdl were called twice - once in
    // create table, once in their respective prepareMock helper methods.
    verify(spanner.getInstanceAdminClient(), times(2)).createInstance(any());
    verify(spanner.getDatabaseAdminClient(), times(2)).createDatabase(any(), any());
    verify(spanner.getDatabaseAdminClient(), times(2))
        .updateDatabaseDdl(
            instanceIdCaptor.capture(),
            databaseIdCaptor.capture(),
            statementCaptor.capture(),
            any());

    String actualInstanceId = instanceIdCaptor.getValue();
    String actualDatabaseId = databaseIdCaptor.getValue();
    Iterable<String> actualStatement = statementCaptor.getValue();

    assertThat(actualInstanceId).matches(TEST_ID + "-\\d{8}-\\d{6}-[a-zA-Z0-9]{6}");

    assertThat(actualDatabaseId).matches(TEST_ID + "_\\d{8}_\\d{6}_[a-zA-Z0-9]{6}");
    assertThat(actualStatement).containsExactlyElementsIn(ImmutableList.of(statement));
  }

  @Test
  public void testExecuteDdlStatementShouldRetryOnResourceExhaustedError()
      throws ExecutionException, InterruptedException {
    //   arrange
    prepareCreateInstanceMock();
    prepareCreateDatabaseMock();
    String statement =
        "CREATE TABLE Singers (\n"
            + "  SingerId   INT64 NOT NULL,\n"
            + "  FirstName  STRING(1024),\n"
            + "  LastName   STRING(1024),\n"
            + ") PRIMARY KEY (SingerId)";

    RuntimeException resourceExhaustedException =
        new RuntimeException(
            "com.google.cloud.spanner.SpannerException: RESOURCE_EXHAUSTED: io.grpc.StatusRuntimeException: RESOURCE_EXHAUSTED: CPU overload detected");
    when(spanner.getDatabaseAdminClient().updateDatabaseDdl(any(), any(), any(), any()).get())
        .thenThrow(resourceExhaustedException)
        .thenReturn(null);

    // act
    testManager.executeDdlStatement(statement);

    // assert
    // verify createInstance, createDatabase, and updateDatabaseDdl were called.
    verify(spanner.getInstanceAdminClient(), times(2)).createInstance(any());
    verify(spanner.getDatabaseAdminClient(), times(2)).createDatabase(any(), any());
    verify(spanner.getDatabaseAdminClient(), times(3))
        .updateDatabaseDdl(
            instanceIdCaptor.capture(),
            databaseIdCaptor.capture(),
            statementCaptor.capture(),
            any());

    String actualInstanceId = instanceIdCaptor.getValue();
    String actualDatabaseId = databaseIdCaptor.getValue();
    Iterable<String> actualStatement = statementCaptor.getValue();

    assertThat(actualInstanceId).matches(TEST_ID + "-\\d{8}-\\d{6}-[a-zA-Z0-9]{6}");

    assertThat(actualDatabaseId).matches(TEST_ID + "_\\d{8}_\\d{6}_[a-zA-Z0-9]{6}");
    assertThat(actualStatement).containsExactlyElementsIn(ImmutableList.of(statement));
  }

  @Test
  public void testWriteSingleRecordShouldWorkWhenSpannerWriteSucceeds()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareTable();
    when(spanner.getDatabaseClient(any()).write(any())).thenReturn(Timestamp.now());
    Mutation testMutation =
        Mutation.newInsertOrUpdateBuilder("SingerId")
            .set("SingerId")
            .to(1)
            .set("FirstName")
            .to("Marc")
            .set("LastName")
            .to("Richards")
            .build();

    // act
    testManager.write(testMutation);

    // assert
    verify(spanner.getDatabaseClient(any())).write(writeMutationCaptor.capture());
    Iterable<Mutation> actualWriteMutation = writeMutationCaptor.getValue();
    assertThat(actualWriteMutation).containsExactlyElementsIn(ImmutableList.of(testMutation));
  }

  @Test
  public void testWriteSingleRecordShouldThrowExceptionWhenCalledBeforeExecuteDdlStatement() {
    // arrange
    Mutation testMutation =
        Mutation.newInsertOrUpdateBuilder("SingerId")
            .set("SingerId")
            .to(1)
            .set("FirstName")
            .to("Marc")
            .set("LastName")
            .to("Richards")
            .build();

    // act & assert
    assertThrows(IllegalStateException.class, () -> testManager.write(testMutation));
  }

  @Test
  public void testWriteSingleRecordShouldThrowExceptionWhenSpannerWriteFails()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareTable();
    when(spanner.getDatabaseClient(any()).write(any())).thenThrow(SpannerException.class);
    Mutation testMutation =
        Mutation.newInsertOrUpdateBuilder("SingerId")
            .set("SingerId")
            .to(1)
            .set("FirstName")
            .to("Marc")
            .set("LastName")
            .to("Richards")
            .build();

    // act & assert
    assertThrows(SpannerResourceManagerException.class, () -> testManager.write(testMutation));
  }

  @Test
  public void testWriteMultipleRecordsShouldWorkWhenSpannerWriteSucceeds()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareTable();
    when(spanner.getDatabaseClient(any()).write(any())).thenReturn(Timestamp.now());
    ImmutableList<Mutation> testMutations =
        ImmutableList.of(
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(1)
                .set("FirstName")
                .to("Marc")
                .set("LastName")
                .to("Richards")
                .build(),
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(2)
                .set("FirstName")
                .to("Catalina")
                .set("LastName")
                .to("Smith")
                .build());

    // act
    testManager.write(testMutations);

    // assert
    verify(spanner.getDatabaseClient(any())).write(writeMutationCaptor.capture());
    Iterable<Mutation> actualWriteMutation = writeMutationCaptor.getValue();

    assertThat(actualWriteMutation).containsExactlyElementsIn(testMutations);
  }

  @Test
  public void testWriteMultipleRecordsShouldThrowExceptionWhenCalledBeforeExecuteDdlStatement() {
    // arrange
    ImmutableList<Mutation> testMutations =
        ImmutableList.of(
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(1)
                .set("FirstName")
                .to("Marc")
                .set("LastName")
                .to("Richards")
                .build(),
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(2)
                .set("FirstName")
                .to("Catalina")
                .set("LastName")
                .to("Smith")
                .build());

    // act & assert
    assertThrows(IllegalStateException.class, () -> testManager.write(testMutations));
  }

  @Test
  public void testWriteMultipleRecordsShouldThrowExceptionWhenSpannerWriteFails()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareTable();
    when(spanner.getDatabaseClient(any()).write(any())).thenThrow(SpannerException.class);
    ImmutableList<Mutation> testMutations =
        ImmutableList.of(
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(1)
                .set("FirstName")
                .to("Marc")
                .set("LastName")
                .to("Richards")
                .build(),
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(2)
                .set("FirstName")
                .to("Catalina")
                .set("LastName")
                .to("Smith")
                .build());

    // act & assert
    assertThrows(SpannerResourceManagerException.class, () -> testManager.write(testMutations));
  }

  @Test
  public void testWriteInTransactionShouldWorkWhenSpannerWriteSucceeds()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareTable();
    DatabaseClient databaseClientMock = mock(DatabaseClient.class);
    TransactionRunner transactionCallableMock = mock(TransactionRunner.class);
    TransactionContext transactionContext = mock(TransactionContext.class);
    when(spanner.getDatabaseClient(any())).thenReturn(databaseClientMock);
    when(databaseClientMock.readWriteTransaction()).thenReturn(transactionCallableMock);
    when(transactionCallableMock.run(any()))
        .thenAnswer(
            invocation -> {
              TransactionRunner.TransactionCallable<Void> callable = invocation.getArgument(0);
              return callable.run(transactionContext);
            });

    ImmutableList<Mutation> testMutations =
        ImmutableList.of(
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(1)
                .set("FirstName")
                .to("Marc")
                .set("LastName")
                .to("Richards")
                .build(),
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(2)
                .set("FirstName")
                .to("Catalina")
                .set("LastName")
                .to("Smith")
                .build());

    // act
    testManager.writeInTransaction(testMutations);

    // assert
    ArgumentCaptor<Iterable<Mutation>> argument = ArgumentCaptor.forClass(Iterable.class);
    verify(transactionContext, times(1)).buffer(argument.capture());
    Iterable<Mutation> capturedMutations = argument.getValue();

    assertThat(capturedMutations).containsExactlyElementsIn(testMutations);
  }

  @Test
  public void testWriteInTransactionShouldThrowExceptionWhenCalledBeforeExecuteDdlStatement() {
    // arrange
    ImmutableList<Mutation> testMutations =
        ImmutableList.of(
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(1)
                .set("FirstName")
                .to("Marc")
                .set("LastName")
                .to("Richards")
                .build(),
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(2)
                .set("FirstName")
                .to("Catalina")
                .set("LastName")
                .to("Smith")
                .build());

    // act & assert
    assertThrows(IllegalStateException.class, () -> testManager.writeInTransaction(testMutations));
  }

  @Test
  public void testWriteInTransactionShouldThrowExceptionWhenSpannerWriteFails()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareTable();
    prepareTable();
    DatabaseClient databaseClientMock = mock(DatabaseClient.class);
    TransactionRunner transactionCallableMock = mock(TransactionRunner.class);
    when(spanner.getDatabaseClient(any())).thenReturn(databaseClientMock);
    when(databaseClientMock.readWriteTransaction()).thenReturn(transactionCallableMock);
    when(transactionCallableMock.run(any()))
        .thenAnswer(
            invocation -> {
              throw SpannerExceptionFactory.newSpannerException(ErrorCode.NOT_FOUND, "Not found");
            });
    ImmutableList<Mutation> testMutations =
        ImmutableList.of(
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(1)
                .set("FirstName")
                .to("Marc")
                .set("LastName")
                .to("Richards")
                .build(),
            Mutation.newInsertOrUpdateBuilder("SingerId")
                .set("SingerId")
                .to(2)
                .set("FirstName")
                .to("Catalina")
                .set("LastName")
                .to("Smith")
                .build());

    // act & assert
    assertThrows(SpannerException.class, () -> testManager.writeInTransaction(testMutations));
  }

  @Test
  public void testExecuteDMLShouldWorkWhenSpannerWriteSucceeds()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareTable();
    DatabaseClient databaseClientMock = mock(DatabaseClient.class);
    TransactionRunner transactionCallableMock = mock(TransactionRunner.class);
    TransactionContext transactionContext = mock(TransactionContext.class);
    when(spanner.getDatabaseClient(any())).thenReturn(databaseClientMock);
    when(databaseClientMock.readWriteTransaction()).thenReturn(transactionCallableMock);
    when(transactionCallableMock.run(any()))
        .thenAnswer(
            invocation -> {
              TransactionRunner.TransactionCallable<Void> callable = invocation.getArgument(0);
              return callable.run(transactionContext);
            });

    ImmutableList<String> testStatements =
        ImmutableList.of(
            "INSERT INTO Singers (SingerId, FirstName, LastName) values (1, 'Marc', 'Richards')",
            "INSERT INTO Singers (SingerId, FirstName, LastName) values (2, 'Catalina', 'Smith')");

    // act
    testManager.executeDMLStatements(testStatements);

    // assert
    ArgumentCaptor<Iterable<Statement>> argument = ArgumentCaptor.forClass(Iterable.class);
    verify(transactionContext, times(1)).batchUpdate(argument.capture());
    Iterable<Statement> capturedStatements = argument.getValue();

    List<Statement> statementList =
        testStatements.stream().map(s -> Statement.of(s)).collect(Collectors.toList());
    assertThat(capturedStatements).containsExactlyElementsIn(statementList);
  }

  @Test
  public void testExecuteDMLShouldThrowExceptionWhenCalledBeforeExecuteDdlStatement() {
    // arrange
    ImmutableList<String> testStatements =
        ImmutableList.of(
            "INSERT INTO Singers (SingerId, FirstName, LastName) values (1, 'Marc', 'Richards')",
            "INSERT INTO Singers (SingerId, FirstName, LastName) values (2, 'Catalina', 'Smith')");

    // act & assert
    assertThrows(
        IllegalStateException.class, () -> testManager.executeDMLStatements(testStatements));
  }

  @Test
  public void testExecuteDMLShouldThrowExceptionWhenSpannerWriteFails()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareTable();
    DatabaseClient databaseClientMock = mock(DatabaseClient.class);
    TransactionRunner transactionCallableMock = mock(TransactionRunner.class);
    when(spanner.getDatabaseClient(any())).thenReturn(databaseClientMock);
    when(databaseClientMock.readWriteTransaction()).thenReturn(transactionCallableMock);
    when(transactionCallableMock.run(any()))
        .thenAnswer(
            invocation -> {
              throw SpannerExceptionFactory.newSpannerException(
                  ErrorCode.DEADLINE_EXCEEDED, "Deadline exceeded while processing the request");
            });

    ImmutableList<String> testStatements =
        ImmutableList.of(
            "INSERT INTO Singers (SingerId, FirstName, LastName) values (1, 'Marc', 'Richards')",
            "INSERT INTO Singers (SingerId, FirstName, LastName) values (2, 'Catalina', 'Smith')");

    // act & assert
    assertThrows(
        SpannerResourceManagerException.class,
        () -> testManager.executeDMLStatements(testStatements));
  }

  @Test
  public void testReadRecordsShouldWorkWhenSpannerReadSucceeds()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareTable();
    when(resultSet.next()).thenReturn(true).thenReturn(true).thenReturn(false);
    Struct struct1 =
        Struct.newBuilder()
            .set("SingerId")
            .to(int64(1))
            .set("FirstName")
            .to(string("Marc"))
            .set("LastName")
            .to(string("Richards"))
            .build();
    Struct struct2 =
        Struct.newBuilder()
            .set("SingerId")
            .to(int64(2))
            .set("FirstName")
            .to(string("Catalina"))
            .set("LastName")
            .to(string("Smith"))
            .build();
    when(resultSet.getCurrentRowAsStruct()).thenReturn(struct1).thenReturn(struct2);
    when(spanner.getDatabaseClient(any()).singleUse().read(any(), any(), any()))
        .thenReturn(resultSet);

    // act
    ImmutableList<Struct> actual =
        testManager.readTableRecords("Singers", "SingerId", "FirstName", "LastName");

    // assert
    ImmutableList<Struct> expected = ImmutableList.of(struct1, struct2);
    assertThat(actual).containsExactlyElementsIn(expected);
  }

  @Test
  public void testReadRecordsWithListOfColumnNamesShouldWorkWhenSpannerReadSucceeds()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareTable();
    when(resultSet.next()).thenReturn(true).thenReturn(false);
    Struct struct =
        Struct.newBuilder()
            .set("SingerId")
            .to(int64(1))
            .set("FirstName")
            .to(string("Marc"))
            .set("LastName")
            .to(string("Richards"))
            .build();
    when(resultSet.getCurrentRowAsStruct()).thenReturn(struct);
    when(spanner.getDatabaseClient(any()).singleUse().read(any(), any(), any()))
        .thenReturn(resultSet);
    ImmutableList<String> columnNames = ImmutableList.of("SingerId", "FirstName", "LastName");

    // act
    ImmutableList<Struct> actual = testManager.readTableRecords("Singers", columnNames);

    // assert
    ImmutableList<Struct> expected = ImmutableList.of(struct);
    assertThat(actual).containsExactlyElementsIn(expected);
  }

  @Test
  public void testReadRecordsShouldThrowExceptionWhenCalledBeforeExecuteDdlStatement() {
    ImmutableList<String> columnNames = ImmutableList.of("SingerId");

    assertThrows(
        IllegalStateException.class, () -> testManager.readTableRecords("Singers", columnNames));
    assertThrows(
        IllegalStateException.class, () -> testManager.readTableRecords("Singers", "SingerId"));
  }

  @Test
  public void testReadRecordsShouldThrowExceptionWhenSpannerReadFails()
      throws ExecutionException, InterruptedException {
    // arrange
    prepareTable();
    when(spanner.getDatabaseClient(any()).singleUse().read(any(), any(), any()))
        .thenThrow(SpannerException.class);
    ImmutableList<String> columnNames = ImmutableList.of("SingerId");

    // act & assert
    assertThrows(
        SpannerResourceManagerException.class,
        () -> testManager.readTableRecords("Singers", "SingerId"));
    assertThrows(
        SpannerResourceManagerException.class,
        () -> testManager.readTableRecords("Singers", columnNames));
  }

  @Test
  public void testCleanupAllShouldThrowExceptionWhenSpannerDeleteInstanceFails() {
    // arrange
    doThrow(SpannerException.class).when(instanceAdminClient).deleteInstance(any());
    when(spanner.getInstanceAdminClient()).thenReturn(instanceAdminClient);
    testManager =
        new SpannerResourceManager(
            SpannerResourceManager.builder(TEST_ID, PROJECT_ID, REGION, DIALECT), spanner);

    // act & assert
    assertThrows(SpannerResourceManagerException.class, () -> testManager.cleanupAll());
  }

  @Test
  public void testCleanupAllShouldWorkWhenSpannerDeleteInstanceSucceeds() {
    // arrange
    doNothing().when(instanceAdminClient).deleteInstance(any());
    when(spanner.getInstanceAdminClient()).thenReturn(instanceAdminClient);
    testManager =
        new SpannerResourceManager(
            SpannerResourceManager.builder(TEST_ID, PROJECT_ID, REGION, DIALECT), spanner);

    // act
    testManager.cleanupAll();

    // assert
    verify(spanner.getInstanceAdminClient()).deleteInstance(any());
    verify(spanner).close();
  }

  @Test
  public void testManagerShouldBeUnusableAfterCleanup() {
    // arrange
    doNothing().when(instanceAdminClient).deleteInstance(any());
    when(spanner.getInstanceAdminClient()).thenReturn(instanceAdminClient);
    when(spanner.isClosed()).thenReturn(true);
    testManager =
        new SpannerResourceManager(
            SpannerResourceManager.builder(TEST_ID, PROJECT_ID, REGION, DIALECT), spanner);
    testManager.cleanupAll();
    String statement =
        "CREATE TABLE Singers (\n"
            + "  SingerId   INT64 NOT NULL,\n"
            + "  FirstName  STRING(1024),\n"
            + "  LastName   STRING(1024),\n"
            + ") PRIMARY KEY (SingerId)";
    Mutation testMutation =
        Mutation.newInsertOrUpdateBuilder("SingerId")
            .set("SingerId")
            .to(1)
            .set("FirstName")
            .to("Marc")
            .set("LastName")
            .to("Richards")
            .build();
    ImmutableList<String> columnNames = ImmutableList.of("SingerId");

    // act & assert
    assertThrows(IllegalStateException.class, () -> testManager.executeDdlStatement(statement));
    assertThrows(
        IllegalStateException.class, () -> testManager.readTableRecords("Singers", "SingerId"));
    assertThrows(
        IllegalStateException.class, () -> testManager.readTableRecords("Singers", columnNames));
    assertThrows(IllegalStateException.class, () -> testManager.write(testMutation));
    assertThrows(
        IllegalStateException.class, () -> testManager.write(ImmutableList.of(testMutation)));
  }

  @Test
  public void testCleanupAllShouldNotDeleteInstanceWhenStatic() {
    // arrange
    doNothing().when(databaseAdminClient).dropDatabase(any(), any());
    when(spanner.getInstanceAdminClient()).thenReturn(instanceAdminClient);
    when(spanner.getDatabaseAdminClient()).thenReturn(databaseAdminClient);
    testManager =
        new SpannerResourceManager(
            SpannerResourceManager.builder(TEST_ID, PROJECT_ID, REGION, DIALECT)
                .setInstanceId("existing-instance")
                .useStaticInstance(),
            spanner);

    // act
    testManager.cleanupAll();

    // assert
    verify(spanner.getDatabaseAdminClient()).dropDatabase(eq("existing-instance"), any());
    verify(spanner.getInstanceAdminClient(), never()).deleteInstance(any());
    verify(spanner).close();
  }

  @Test
  public void testCollectMetricsThrowsExceptionWhenMonitoringClientIsNotInitialized() {
    assertThrows(
        SpannerResourceManagerException.class, () -> testManager.collectMetrics(new HashMap<>()));
  }

  @Test
  public void testCollectMetricsShouldThrowExceptionWhenDatabaseIsNotCreated() {
    when(monitoringClient.getAggregatedMetric(any(), any(), any(), any())).thenReturn(1.1);
    SpannerResourceManager testManagerWithMonitoringClient =
        new SpannerResourceManager(
            SpannerResourceManager.builder(TEST_ID, PROJECT_ID, REGION, DIALECT)
                .setMonitoringClient(monitoringClient),
            spanner);
    assertThrows(
        IllegalStateException.class,
        () -> testManagerWithMonitoringClient.collectMetrics(new HashMap<>()));
  }

  @Test
  public void testCollectMetricsShouldWorkWhenMonitoringClientAndDatabaseIsInitialized()
      throws ExecutionException, InterruptedException {
    when(monitoringClient.getAggregatedMetric(any(), any(), any(), any())).thenReturn(1.1);
    SpannerResourceManager testManagerWithMonitoringClient =
        new SpannerResourceManager(
            SpannerResourceManager.builder(TEST_ID, PROJECT_ID, REGION, DIALECT)
                .setMonitoringClient(monitoringClient),
            spanner);
    prepareCreateInstanceMock();
    prepareCreateDatabaseMock();
    testManagerWithMonitoringClient.executeDdlStatement("");

    Map<String, Double> metrics = new HashMap<>();
    testManagerWithMonitoringClient.collectMetrics(metrics);

    assertEquals(2, metrics.size());
    assertEquals(1.1, metrics.get("Spanner_AverageCpuUtilization"), 0.0);
    assertEquals(1.1, metrics.get("Spanner_MaxCpuUtilization"), 0.0);
    ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);

    verify(monitoringClient, times(2))
        .getAggregatedMetric(projectIdCaptor.capture(), filterCaptor.capture(), any(), any());
    assertThat(projectIdCaptor.getValue()).isEqualTo(PROJECT_ID);
    assertThat(filterCaptor.getValue())
        .contains("metric.type=\"spanner.googleapis.com/instance/cpu/utilization\"");
  }

  private void prepareCreateDatabaseMock() throws ExecutionException, InterruptedException {
    Mockito.lenient()
        .when(spanner.getDatabaseAdminClient().createDatabase(any(), any()).get())
        .thenReturn(database);
  }

  private void prepareUpdateDatabaseMock() throws ExecutionException, InterruptedException {
    Mockito.lenient()
        .when(spanner.getDatabaseAdminClient().updateDatabaseDdl(any(), any(), any(), any()).get())
        .thenReturn(null);
  }

  private void prepareTable() throws ExecutionException, InterruptedException {
    prepareCreateInstanceMock();
    prepareCreateDatabaseMock();
    prepareUpdateDatabaseMock();
    testManager.executeDdlStatement("");
  }
}
