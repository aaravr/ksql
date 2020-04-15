/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.planner.plan;

import static io.confluent.ksql.metastore.model.DataSource.DataSourceType;
import static io.confluent.ksql.metastore.model.DataSource.DataSourceType.KSTREAM;
import static io.confluent.ksql.metastore.model.DataSource.DataSourceType.KTABLE;
import static io.confluent.ksql.name.ColumnName.of;
import static io.confluent.ksql.planner.plan.JoinNode.JoinType.INNER;
import static io.confluent.ksql.planner.plan.JoinNode.JoinType.LEFT;
import static io.confluent.ksql.planner.plan.JoinNode.JoinType.OUTER;
import static io.confluent.ksql.planner.plan.PlanTestUtil.SOURCE_NODE;
import static io.confluent.ksql.planner.plan.PlanTestUtil.TRANSFORM_NODE;
import static io.confluent.ksql.planner.plan.PlanTestUtil.getNodeByName;
import static io.confluent.ksql.schema.ksql.ColumnRef.withoutSource;
import static io.confluent.ksql.schema.ksql.FormatOptions.noEscape;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Optional.empty;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.ddl.commands.KsqlTopic;
import io.confluent.ksql.execution.streams.KSPlanBuilder;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.function.InternalFunctionRegistry;
import io.confluent.ksql.metastore.MetaStore;
import io.confluent.ksql.metastore.model.DataSource;
import io.confluent.ksql.metastore.model.KeyField;
import io.confluent.ksql.metastore.model.KeyField.LegacyField;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.parser.tree.WithinExpression;
import io.confluent.ksql.planner.plan.JoinNode.JoinType;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.schema.ksql.Column;
import io.confluent.ksql.schema.ksql.ColumnRef;
import io.confluent.ksql.schema.ksql.FormatOptions;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.ValueFormat;
import io.confluent.ksql.services.KafkaTopicClient;
import io.confluent.ksql.services.ServiceContext;
import io.confluent.ksql.structured.SchemaKStream;
import io.confluent.ksql.structured.SchemaKTable;
import io.confluent.ksql.testutils.AnalysisTestUtil;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.MetaStoreFixture;
import io.confluent.ksql.util.SchemaUtil;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyDescription;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;


@SuppressWarnings({"SameParameterValue", "OptionalGetWithoutIsPresent"})
@RunWith(MockitoJUnitRunner.class)
public class JoinNodeTest {

  private static final LogicalSchema LEFT_SOURCE_SCHEMA = LogicalSchema.builder()
      .valueColumn(ColumnName.of("C0"), SqlTypes.BIGINT)
      .valueColumn(ColumnName.of("L1"), SqlTypes.STRING)
      .build();

  private static final LogicalSchema RIGHT_SOURCE_SCHEMA = LogicalSchema.builder()
      .valueColumn(ColumnName.of("C0"), SqlTypes.BIGINT)
      .valueColumn(ColumnName.of("R1"), SqlTypes.STRING)
      .build();

  private static final SourceName LEFT_ALIAS = SourceName.of("left");
  private static final SourceName RIGHT_ALIAS = SourceName.of("right");

  private static final LogicalSchema LEFT_NODE_SCHEMA = LEFT_SOURCE_SCHEMA
      .withMetaAndKeyColsInValue()
      .withAlias(LEFT_ALIAS);

  private static final LogicalSchema RIGHT_NODE_SCHEMA = RIGHT_SOURCE_SCHEMA
      .withMetaAndKeyColsInValue()
      .withAlias(RIGHT_ALIAS);

  private static final LogicalSchema JOIN_SCHEMA = joinSchema();

  private static final Optional<ColumnRef> NO_KEY_FIELD = Optional.empty();
  private static final ValueFormat VALUE_FORMAT = ValueFormat.of(FormatInfo.of(Format.JSON));
  private static final ValueFormat OTHER_FORMAT = ValueFormat.of(FormatInfo.of(Format.DELIMITED));
  private final KsqlConfig ksqlConfig = new KsqlConfig(new HashMap<>());
  private StreamsBuilder builder;
  private JoinNode joinNode;

  private static final ColumnRef LEFT_JOIN_FIELD_REF = ColumnRef.of(LEFT_ALIAS, ColumnName.of("C0"));
  private static final ColumnRef RIGHT_JOIN_FIELD_REF = ColumnRef.of(RIGHT_ALIAS, ColumnName.of("R1"));

  private static final KeyField leftJoinField = KeyField
      .of(LEFT_JOIN_FIELD_REF, Column.of(LEFT_JOIN_FIELD_REF, SqlTypes.STRING));

  private static final KeyField rightJoinField = KeyField
      .of(RIGHT_JOIN_FIELD_REF, Column.of(RIGHT_JOIN_FIELD_REF, SqlTypes.STRING));

  private static final Optional<WithinExpression> WITHIN_EXPRESSION =
      Optional.of(new WithinExpression(10, TimeUnit.SECONDS));

  private static final PlanNodeId nodeId = new PlanNodeId("join");
  private static final QueryId queryId = new QueryId("join-query");
  private static final QueryContext.Stacker CONTEXT_STACKER =
      new QueryContext.Stacker().push(nodeId.toString());

  @Mock
  private DataSource<?> leftSource;
  @Mock
  private DataSource<?> rightSource;
  @Mock
  private DataSourceNode left;
  @Mock
  private DataSourceNode right;
  @Mock
  private SchemaKStream<String> leftSchemaKStream;
  @Mock
  private SchemaKStream<String> rightSchemaKStream;
  @Mock
  private SchemaKTable<String> leftSchemaKTable;
  @Mock
  private SchemaKTable<String> rightSchemaKTable;
  @Mock
  private KsqlQueryBuilder ksqlStreamBuilder;
  @Mock
  private FunctionRegistry functionRegistry;
  @Mock
  private KafkaTopicClient mockKafkaTopicClient;

  @Before
  public void setUp() {
    builder = new StreamsBuilder();

    final ServiceContext serviceContext = mock(ServiceContext.class);
    when(serviceContext.getTopicClient())
        .thenReturn(mockKafkaTopicClient);

    when(ksqlStreamBuilder.getKsqlConfig()).thenReturn(ksqlConfig);
    when(ksqlStreamBuilder.getStreamsBuilder()).thenReturn(builder);
    when(ksqlStreamBuilder.getServiceContext()).thenReturn(serviceContext);
    when(ksqlStreamBuilder.withKsqlConfig(any())).thenReturn(ksqlStreamBuilder);
    when(ksqlStreamBuilder.getFunctionRegistry()).thenReturn(functionRegistry);
    when(ksqlStreamBuilder.buildNodeContext(any())).thenAnswer(inv ->
        new QueryContext.Stacker()
            .push(inv.getArgument(0).toString()));

    when(left.getAlias()).thenReturn(LEFT_ALIAS);
    when(right.getAlias()).thenReturn(RIGHT_ALIAS);

    when(left.getDataSourceType()).thenReturn(DataSourceType.KSTREAM);
    when(right.getDataSourceType()).thenReturn(DataSourceType.KTABLE);

    when(left.getSchema()).thenReturn(LEFT_NODE_SCHEMA);
    when(right.getSchema()).thenReturn(RIGHT_NODE_SCHEMA);

    when(left.getPartitions(mockKafkaTopicClient)).thenReturn(2);
    when(right.getPartitions(mockKafkaTopicClient)).thenReturn(2);

    setUpSource(left, VALUE_FORMAT, leftSource, "Foobar1");
    setUpSource(right, OTHER_FORMAT, rightSource, "Foobar2");

    when(leftSchemaKStream.getKeyField()).thenReturn(leftJoinField);
    when(leftSchemaKTable.getKeyField()).thenReturn(leftJoinField);
    when(rightSchemaKTable.getKeyField()).thenReturn(rightJoinField);
  }

  @Test
  public void shouldThrowIfLeftKeyFieldNotInLeftSchema() {
    // Then:
    // When:
    final Exception e = assertThrows(
        IllegalArgumentException.class,
        () -> new JoinNode(
            nodeId,
            emptyList(),
            LEFT,
            left,
            right,
            withoutSource(of("won't find me")),
            RIGHT_JOIN_FIELD_REF,
            empty()
        )
    );

    // Then:
    assertThat(e.getMessage(), containsString("Invalid join field"));
  }

  @Test
  public void shouldThrowIfRightKeyFieldNotInRightSchema() {
    // Then:
    // When:
    final Exception e = assertThrows(
        IllegalArgumentException.class,
        () -> new JoinNode(
            nodeId,
            emptyList(),
            LEFT,
            left,
            right,
            LEFT_JOIN_FIELD_REF,
            withoutSource(of("won't find me")),
            empty()
        )
    );

    // Then:
    assertThat(e.getMessage(), containsString("Invalid join field"));
  }

  @Test
  public void shouldReturnLeftJoinKeyAsKeyField() {
    // When:
    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        Optional.empty()
    );

    // Then:
    assertThat(joinNode.getKeyField().ref(), is(Optional.of(LEFT_JOIN_FIELD_REF)));
  }

  @Test
  public void shouldBuildSourceNode() {
    setupTopicClientExpectations(1, 1);
    buildJoin();
    final TopologyDescription.Source node = (TopologyDescription.Source) getNodeByName(
        builder.build(), SOURCE_NODE);
    final List<String> successors = node.successors().stream().map(TopologyDescription.Node::name)
        .collect(Collectors.toList());
    assertThat(node.predecessors(), equalTo(Collections.emptySet()));
    assertThat(successors, equalTo(Collections.singletonList(TRANSFORM_NODE)));
    assertThat(node.topicSet(), equalTo(ImmutableSet.of("test2")));
  }

  @Test
  public void shouldUseLegacyNameForReduceTopicIfOptimizationsOff() {
    setupTopicClientExpectations(1, 1);
    when(ksqlStreamBuilder.getKsqlConfig()).thenReturn(
        ksqlConfig.overrideBreakingConfigsWithOriginalValues(
            ImmutableMap.of(
                KsqlConfig.KSQL_USE_NAMED_INTERNAL_TOPICS,
                KsqlConfig.KSQL_USE_NAMED_INTERNAL_TOPICS_OFF)
        )
    );

    buildJoin();

    final Topology topology = builder.build();
    final TopologyDescription.Processor leftJoin
        = (TopologyDescription.Processor) getNodeByName(topology, "KSTREAM-LEFTJOIN-0000000015");
    assertThat(
        leftJoin.stores(),
        equalTo(Utils.mkSet("KSTREAM-AGGREGATE-STATE-STORE-0000000004")));
  }

  @Test
  @Ignore // ignore this test until Kafka merges KIP-479
  public void shouldHaveLeftJoin() {
    setupTopicClientExpectations(1, 1);
    buildJoin();
    final Topology topology = builder.build();
    final TopologyDescription.Processor leftJoin
        = (TopologyDescription.Processor) getNodeByName(topology, "KSTREAM-LEFTJOIN-0000000014");
    final List<String> predecessors = leftJoin.predecessors().stream()
        .map(TopologyDescription.Node::name).collect(Collectors.toList());
    assertThat(leftJoin.stores(), equalTo(Utils.mkSet("KafkaTopic_Right-reduce")));
    assertThat(predecessors, equalTo(Collections.singletonList("KSTREAM-SOURCE-0000000013")));
  }

  @Test
  public void shouldThrowOnPartitionMismatch() {
    // Given:
    setupTopicClientExpectations(1, 2);

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> buildJoin(
            "SELECT t1.col0, t2.col0, t2.col1 "
                + "FROM test1 t1 LEFT JOIN test2 t2 ON t1.col0 = t2.col0 EMIT CHANGES;"
        )
    );

    // Then:
    assertThat(e.getMessage(), containsString("Can't join TEST1 with TEST2 since the number of partitions don't match. TEST1 "
        + "partitions = 1; TEST2 partitions = 2. Please repartition either one so that the "
        + "number of partitions match."));
  }

  @Test
  public void shouldPerformStreamToStreamLeftJoin() {
    // Given:
    setupStream(left, leftSchemaKStream);
    setupStream(right, rightSchemaKStream);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        WITHIN_EXPRESSION
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).leftJoin(
        eq(rightSchemaKStream),
        eq(JOIN_SCHEMA),
        eq(leftJoinField),
        eq(WITHIN_EXPRESSION.get().joinWindow()),
        eq(VALUE_FORMAT),
        eq(OTHER_FORMAT),
        eq(CONTEXT_STACKER)
    );
  }

  @Test
  public void shouldPerformStreamToStreamInnerJoin() {
    // Given:
    setupStream(left, leftSchemaKStream);
    setupStream(right, rightSchemaKStream);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.INNER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        WITHIN_EXPRESSION
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).join(
        eq(rightSchemaKStream),
        eq(JOIN_SCHEMA),
        eq(leftJoinField),
        eq(WITHIN_EXPRESSION.get().joinWindow()),
        eq(VALUE_FORMAT),
        eq(OTHER_FORMAT),
        eq(CONTEXT_STACKER)
    );
  }

  @Test
  public void shouldPerformStreamToStreamOuterJoin() {
    // Given:
    setupStream(left, leftSchemaKStream);
    setupStream(right, rightSchemaKStream);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        WITHIN_EXPRESSION
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).outerJoin(
        eq(rightSchemaKStream),
        eq(JOIN_SCHEMA),
        eq(leftJoinField.withName(Optional.empty())),
        eq(WITHIN_EXPRESSION.get().joinWindow()),
        eq(VALUE_FORMAT),
        eq(OTHER_FORMAT),
        eq(CONTEXT_STACKER)
    );
  }

  @Test
  public void shouldNotPerformStreamStreamJoinWithoutJoinWindow() {
    // Given:
    when(left.getDataSourceType()).thenReturn(KSTREAM);
    when(right.getDataSourceType()).thenReturn(KSTREAM);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        emptyList(),
        INNER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        empty()
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString("Stream-Stream joins must have a WITHIN clause specified. None was provided."));
  }

  @Test
  public void shouldNotPerformJoinIfInputPartitionsMisMatch() {
    // Given:
    when(left.getPartitions(mockKafkaTopicClient)).thenReturn(3);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        emptyList(),
        OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        WITHIN_EXPRESSION
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString("Can't join Foobar1 with Foobar2 since the number of partitions don't match."));
  }

  @Test
  public void shouldFailJoinIfTableCriteriaColumnIsNotKey() {
    // Given:
    setupStream(left, leftSchemaKStream);
    setupTable(right, rightSchemaKTable);

    final ColumnRef rightCriteriaColumn =
        getNonKeyColumn(RIGHT_SOURCE_SCHEMA, RIGHT_ALIAS, RIGHT_JOIN_FIELD_REF);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        emptyList(),
        LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        rightCriteriaColumn,
        empty()
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(format(
        "Source table (%s) key column (%s) is not the column used in the join criteria (%s). "
            + "Only the table's key column or 'ROWKEY' is supported in the join criteria.",
        RIGHT_ALIAS.toString(noEscape()),
        RIGHT_JOIN_FIELD_REF.toString(noEscape()),
        rightCriteriaColumn.toString(noEscape())
    )));
  }

  @Test
  public void shouldFailJoinIfTableHasNoKeyAndJoinFieldIsNotRowKey() {
    // Given:
    setupStream(left, leftSchemaKStream);
    setupTable(right, rightSchemaKTable, NO_KEY_FIELD);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        emptyList(),
        LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        empty()
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString("Source table (" + RIGHT_ALIAS.name() + ") has no key column defined. "
        + "Only 'ROWKEY' is supported in the join criteria."));
  }

  @Test
  public void shouldHandleJoinIfTableHasNoKeyAndJoinFieldIsRowKey() {
    // Given:
    setupStream(left, leftSchemaKStream);
    setupTable(right, rightSchemaKTable, NO_KEY_FIELD);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        ColumnRef.of(SourceName.of("right"), ColumnName.of("ROWKEY")),
        Optional.empty()
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).leftJoin(
        eq(rightSchemaKTable),
        eq(JOIN_SCHEMA),
        eq(leftJoinField),
        eq(VALUE_FORMAT),
        eq(CONTEXT_STACKER)
    );
  }

  @Test
  public void shouldPerformStreamToTableLeftJoin() {
    // Given:
    setupStream(left, leftSchemaKStream);
    setupTable(right, rightSchemaKTable);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        Optional.empty()
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).leftJoin(
        eq(rightSchemaKTable),
        eq(JOIN_SCHEMA),
        eq(leftJoinField),
        eq(VALUE_FORMAT),
        eq(CONTEXT_STACKER)
    );
  }

  @Test
  public void shouldPerformStreamToTableInnerJoin() {
    // Given:
    setupStream(left, leftSchemaKStream);
    setupTable(right, rightSchemaKTable);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.INNER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        Optional.empty()
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).join(
        eq(rightSchemaKTable),
        eq(JOIN_SCHEMA),
        eq(leftJoinField),
        eq(VALUE_FORMAT),
        eq(CONTEXT_STACKER)
    );
  }

  @Test
  public void shouldNotAllowStreamToTableOuterJoin() {
    // Given:
    setupStream(left, leftSchemaKStream);
    setupTable(right, rightSchemaKTable);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        emptyList(),
        OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        empty()
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString("Full outer joins between streams and tables are not supported."));
  }

  @Test
  public void shouldNotPerformStreamToTableJoinIfJoinWindowIsSpecified() {
    // Given:
    when(left.getDataSourceType()).thenReturn(KSTREAM);
    when(right.getDataSourceType()).thenReturn(KTABLE);

    final WithinExpression withinExpression = new WithinExpression(10, SECONDS);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        emptyList(),
        OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        Optional.of(withinExpression)
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString("A window definition was provided for a Stream-Table join."));
  }

  @Test
  public void shouldFailTableTableJoinIfLeftCriteriaColumnIsNotKey() {
    // Given:
    setupTable(left, leftSchemaKTable);
    setupTable(right, rightSchemaKTable);

    final ColumnRef leftCriteriaColumn = getNonKeyColumn(LEFT_SOURCE_SCHEMA, LEFT_ALIAS,
        LEFT_JOIN_FIELD_REF);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        emptyList(),
        LEFT,
        left,
        right,
        leftCriteriaColumn,
        RIGHT_JOIN_FIELD_REF,
        empty()
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(format(
        "Source table (%s) key column (%s) is not the column used in the join criteria (%s). "
            + "Only the table's key column or 'ROWKEY' is supported in the join criteria.",
        LEFT_ALIAS.toString(noEscape()),
        LEFT_JOIN_FIELD_REF.toString(noEscape()),
        leftCriteriaColumn.toString(noEscape())
    )));
  }

  @Test
  public void shouldFailTableTableJoinIfRightCriteriaColumnIsNotKey() {
    // Given:
    setupTable(left, leftSchemaKTable);
    setupTable(right, rightSchemaKTable);

    final ColumnRef rightCriteriaColumn =
        getNonKeyColumn(RIGHT_SOURCE_SCHEMA, RIGHT_ALIAS, RIGHT_JOIN_FIELD_REF);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        emptyList(),
        LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        rightCriteriaColumn,
        empty()
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(format(
        "Source table (%s) key column (%s) is not the column used in the join criteria (%s). "
            + "Only the table's key column or 'ROWKEY' is supported in the join criteria.",
        RIGHT_ALIAS.toString(noEscape()),
        RIGHT_JOIN_FIELD_REF.toString(noEscape()),
        rightCriteriaColumn.toString(noEscape())
    )));
  }

  @Test
  public void shouldPerformTableToTableInnerJoin() {
    // Given:
    setupTable(left, leftSchemaKTable);
    setupTable(right, rightSchemaKTable);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.INNER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        Optional.empty()
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKTable).join(
        eq(rightSchemaKTable),
        eq(JOIN_SCHEMA),
        eq(leftJoinField),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldPerformTableToTableLeftJoin() {
    // Given:
    setupTable(left, leftSchemaKTable);
    setupTable(right, rightSchemaKTable);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        Optional.empty()
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKTable).leftJoin(
        eq(rightSchemaKTable),
        eq(JOIN_SCHEMA),
        eq(leftJoinField),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldPerformTableToTableOuterJoin() {
    // Given:
    setupTable(left, leftSchemaKTable);
    setupTable(right, rightSchemaKTable);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        Optional.empty()
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKTable).outerJoin(
        eq(rightSchemaKTable),
        eq(JOIN_SCHEMA),
        eq(leftJoinField.withName(Optional.empty())),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldNotPerformTableToTableJoinIfJoinWindowIsSpecified() {
    // Given:
    when(left.getDataSourceType()).thenReturn(KTABLE);
    when(right.getDataSourceType()).thenReturn(KTABLE);

    final WithinExpression withinExpression = new WithinExpression(10, SECONDS);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        emptyList(),
        OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        Optional.of(withinExpression)
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString("A window definition was provided for a Table-Table join."));
  }

  @Test
  public void shouldHaveFullyQualifiedJoinSchema() {
    // When:
    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        Optional.empty()
    );

    // When:
    assertThat(joinNode.getSchema(), is(LogicalSchema.builder()
        .valueColumn(LEFT_ALIAS, ColumnName.of("ROWTIME"), SqlTypes.BIGINT)
        .valueColumn(LEFT_ALIAS, ColumnName.of("ROWKEY"), SqlTypes.STRING)
        .valueColumn(LEFT_ALIAS, ColumnName.of("C0"), SqlTypes.BIGINT)
        .valueColumn(LEFT_ALIAS, ColumnName.of("L1"), SqlTypes.STRING)
        .valueColumn(RIGHT_ALIAS, ColumnName.of("ROWTIME"), SqlTypes.BIGINT)
        .valueColumn(RIGHT_ALIAS, ColumnName.of("ROWKEY"), SqlTypes.STRING)
        .valueColumn(RIGHT_ALIAS, ColumnName.of("C0"), SqlTypes.BIGINT)
        .valueColumn(RIGHT_ALIAS, ColumnName.of("R1"), SqlTypes.STRING)
        .build()
    ));
  }

  @Test
  public void shouldSelectLeftKeyField() {
    // Given:
    setupStream(left, leftSchemaKStream);
    setupStream(right, rightSchemaKStream);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        WITHIN_EXPRESSION
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).selectKey(
        eq(LEFT_JOIN_FIELD_REF),
        anyBoolean(),
        any()
    );
  }

  @Test
  public void shouldNotUseSourceSerdeOptionsForInternalTopics() {
    // Given:
    setupStream(left, leftSchemaKStream);
    setupStream(right, rightSchemaKStream);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        Collections.emptyList(),
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_REF,
        RIGHT_JOIN_FIELD_REF,
        WITHIN_EXPRESSION
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSource, never()).getSerdeOptions();
    verify(rightSource, never()).getSerdeOptions();
  }

  @SuppressWarnings("unchecked")
  private void setupTable(
      final DataSourceNode node,
      final SchemaKTable table
  ) {
    when(node.buildStream(ksqlStreamBuilder)).thenReturn(table);
    final LogicalSchema schema = node.getSchema();
    when(table.getSchema()).thenReturn(schema);
    when(node.getDataSourceType()).thenReturn(DataSourceType.KTABLE);
  }

  private void setupTable(
      final DataSourceNode node,
      final SchemaKTable table,
      final Optional<ColumnRef> keyFieldName
  ) {
    setupTable(node, table);

    final LogicalSchema schema = node.getSchema();

    final Optional<LegacyField> keyField = keyFieldName
        .map(key -> schema.findValueColumn(key).orElseThrow(AssertionError::new))
        .map(field -> LegacyField.of(field.ref(), field.type()));

    when(table.getKeyField()).thenReturn(KeyField.of(keyFieldName, keyField));
  }

  @SuppressWarnings("unchecked")
  private void setupStream(
      final DataSourceNode node,
      final SchemaKStream stream
  ) {
    when(node.buildStream(ksqlStreamBuilder)).thenReturn(stream);
    final LogicalSchema schema = node.getSchema();
    when(stream.getSchema()).thenReturn(schema);
    when(stream.selectKey(any(), eq(true), any())).thenReturn(stream);
    when(node.getDataSourceType()).thenReturn(DataSourceType.KSTREAM);
  }

  @SuppressWarnings("Duplicates")
  private static LogicalSchema joinSchema() {
    return LogicalSchema.builder()
        .keyColumn(SchemaUtil.ROWKEY_NAME, SqlTypes.STRING)
        .valueColumns(LEFT_NODE_SCHEMA.value())
        .valueColumns(RIGHT_NODE_SCHEMA.value())
        .build();
  }

  private void buildJoin() {
    buildJoin(
        "SELECT t1.col1, t2.col1, t2.col4, col5, t2.col2 "
            + "FROM test1 t1 LEFT JOIN test2 t2 "
            + "ON t1.col1 = t2.col0 EMIT CHANGES;"
    );
  }

  private void buildJoin(final String queryString) {
    buildJoinNode(queryString);
    final SchemaKStream stream = joinNode.buildStream(ksqlStreamBuilder);
    if (stream instanceof SchemaKTable) {
      final SchemaKTable table = (SchemaKTable) stream;
      table.getSourceTableStep().build(new KSPlanBuilder(ksqlStreamBuilder));
    } else {
      stream.getSourceStep().build(new KSPlanBuilder(ksqlStreamBuilder));
    }
  }

  private void buildJoinNode(final String queryString) {
    final MetaStore metaStore = MetaStoreFixture.getNewMetaStore(new InternalFunctionRegistry());

    final KsqlBareOutputNode planNode =
        (KsqlBareOutputNode) AnalysisTestUtil.buildLogicalPlan(ksqlConfig, queryString, metaStore);

    joinNode = (JoinNode) ((ProjectNode) planNode.getSource()).getSource();
  }

  private void setupTopicClientExpectations(final int streamPartitions, final int tablePartitions) {
    final Node node = new Node(0, "localhost", 9091);

    final List<TopicPartitionInfo> streamPartitionInfoList =
        IntStream.range(0, streamPartitions)
            .mapToObj(
                p -> new TopicPartitionInfo(p, node, Collections.emptyList(),
                    Collections.emptyList()))
            .collect(Collectors.toList());

    when(mockKafkaTopicClient.describeTopic("test1"))
        .thenReturn(new TopicDescription("test1", false, streamPartitionInfoList));

    final List<TopicPartitionInfo> tablePartitionInfoList =
        IntStream.range(0, tablePartitions)
            .mapToObj(
                p -> new TopicPartitionInfo(p, node, Collections.emptyList(),
                    Collections.emptyList()))
            .collect(Collectors.toList());

    when(mockKafkaTopicClient.describeTopic("test2"))
        .thenReturn(new TopicDescription("test2", false, tablePartitionInfoList));
  }

  private static Optional<Column> getColumn(final LogicalSchema schema, final Predicate<ColumnName> filter) {
    return schema.value().stream()
        .filter(col -> filter.test(col.name()))
        .findFirst();
  }

  private static ColumnRef getNonKeyColumn(
      final LogicalSchema schema,
      final SourceName alias,
      final ColumnRef keyName
  ) {
    final ImmutableList<ColumnName> blackList = ImmutableList.of(
        SchemaUtil.ROWKEY_NAME,
        SchemaUtil.ROWTIME_NAME,
        keyName.name()
    );

    final Column column =
        getColumn(schema, s -> !blackList.contains(s))
            .orElseThrow(AssertionError::new);

    final Column field = schema.findValueColumn(column.ref()).get();
    return ColumnRef.of(alias, field.name());
  }

  @SuppressWarnings("unchecked")
  private static void setUpSource(
      final DataSourceNode node,
      final ValueFormat valueFormat,
      final DataSource<?> dataSource,
      final String name
  ) {
    when(dataSource.getName()).thenReturn(SourceName.of(name));
    when(node.getDataSource()).thenReturn((DataSource)dataSource);

    final KsqlTopic ksqlTopic = mock(KsqlTopic.class);
    when(ksqlTopic.getValueFormat()).thenReturn(valueFormat);
    when(dataSource.getKsqlTopic()).thenReturn(ksqlTopic);
  }
}
