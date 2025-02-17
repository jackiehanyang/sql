/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.spark.execution.statestore;

import static org.opensearch.sql.spark.data.constants.SparkConstants.SPARK_REQUEST_BUFFER_INDEX_NAME;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.DocWriteResponse;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.action.update.UpdateRequest;
import org.opensearch.action.update.UpdateResponse;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.action.ActionFuture;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.sql.spark.asyncquery.model.AsyncQueryJobMetadata;
import org.opensearch.sql.spark.execution.session.SessionModel;
import org.opensearch.sql.spark.execution.session.SessionState;
import org.opensearch.sql.spark.execution.statement.StatementModel;
import org.opensearch.sql.spark.execution.statement.StatementState;

/**
 * State Store maintain the state of Session and Statement. State State create/update/get doc on
 * index regardless user FGAC permissions.
 */
@RequiredArgsConstructor
public class StateStore {
  public static String SETTINGS_FILE_NAME = "query_execution_request_settings.yml";
  public static String MAPPING_FILE_NAME = "query_execution_request_mapping.yml";
  public static Function<String, String> DATASOURCE_TO_REQUEST_INDEX =
      datasourceName -> String.format("%s_%s", SPARK_REQUEST_BUFFER_INDEX_NAME, datasourceName);

  private static final Logger LOG = LogManager.getLogger();

  private final Client client;
  private final ClusterService clusterService;

  protected <T extends StateModel> T create(
      T st, StateModel.CopyBuilder<T> builder, String indexName) {
    try {
      if (!this.clusterService.state().routingTable().hasIndex(indexName)) {
        createIndex(indexName);
      }
      IndexRequest indexRequest =
          new IndexRequest(indexName)
              .id(st.getId())
              .source(st.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS))
              .setIfSeqNo(st.getSeqNo())
              .setIfPrimaryTerm(st.getPrimaryTerm())
              .create(true)
              .setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL);
      try (ThreadContext.StoredContext ignored =
          client.threadPool().getThreadContext().stashContext()) {
        IndexResponse indexResponse = client.index(indexRequest).actionGet();
        if (indexResponse.getResult().equals(DocWriteResponse.Result.CREATED)) {
          LOG.debug("Successfully created doc. id: {}", st.getId());
          return builder.of(st, indexResponse.getSeqNo(), indexResponse.getPrimaryTerm());
        } else {
          throw new RuntimeException(
              String.format(
                  Locale.ROOT,
                  "Failed create doc. id: %s, error: %s",
                  st.getId(),
                  indexResponse.getResult().getLowercase()));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected <T extends StateModel> Optional<T> get(
      String sid, StateModel.FromXContent<T> builder, String indexName) {
    try {
      if (!this.clusterService.state().routingTable().hasIndex(indexName)) {
        createIndex(indexName);
        return Optional.empty();
      }
      GetRequest getRequest = new GetRequest().index(indexName).id(sid).refresh(true);
      try (ThreadContext.StoredContext ignored =
          client.threadPool().getThreadContext().stashContext()) {
        GetResponse getResponse = client.get(getRequest).actionGet();
        if (getResponse.isExists()) {
          XContentParser parser =
              XContentType.JSON
                  .xContent()
                  .createParser(
                      NamedXContentRegistry.EMPTY,
                      LoggingDeprecationHandler.INSTANCE,
                      getResponse.getSourceAsString());
          parser.nextToken();
          return Optional.of(
              builder.fromXContent(parser, getResponse.getSeqNo(), getResponse.getPrimaryTerm()));
        } else {
          return Optional.empty();
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected <T extends StateModel, S> T updateState(
      T st, S state, StateModel.StateCopyBuilder<T, S> builder, String indexName) {
    try {
      T model = builder.of(st, state, st.getSeqNo(), st.getPrimaryTerm());
      UpdateRequest updateRequest =
          new UpdateRequest()
              .index(indexName)
              .id(model.getId())
              .setIfSeqNo(model.getSeqNo())
              .setIfPrimaryTerm(model.getPrimaryTerm())
              .doc(model.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS))
              .fetchSource(true)
              .setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL);
      try (ThreadContext.StoredContext ignored =
          client.threadPool().getThreadContext().stashContext()) {
        UpdateResponse updateResponse = client.update(updateRequest).actionGet();
        if (updateResponse.getResult().equals(DocWriteResponse.Result.UPDATED)) {
          LOG.debug("Successfully update doc. id: {}", st.getId());
          return builder.of(
              model, state, updateResponse.getSeqNo(), updateResponse.getPrimaryTerm());
        } else {
          throw new RuntimeException(
              String.format(
                  Locale.ROOT,
                  "Failed update doc. id: %s, error: %s",
                  st.getId(),
                  updateResponse.getResult().getLowercase()));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void createIndex(String indexName) {
    try {
      CreateIndexRequest createIndexRequest = new CreateIndexRequest(indexName);
      createIndexRequest
          .mapping(loadConfigFromResource(MAPPING_FILE_NAME), XContentType.YAML)
          .settings(loadConfigFromResource(SETTINGS_FILE_NAME), XContentType.YAML);
      ActionFuture<CreateIndexResponse> createIndexResponseActionFuture;
      try (ThreadContext.StoredContext ignored =
          client.threadPool().getThreadContext().stashContext()) {
        createIndexResponseActionFuture = client.admin().indices().create(createIndexRequest);
      }
      CreateIndexResponse createIndexResponse = createIndexResponseActionFuture.actionGet();
      if (createIndexResponse.isAcknowledged()) {
        LOG.info("Index: {} creation Acknowledged", indexName);
      } else {
        throw new RuntimeException("Index creation is not acknowledged.");
      }
    } catch (Throwable e) {
      throw new RuntimeException(
          "Internal server error while creating" + indexName + " index:: " + e.getMessage());
    }
  }

  private String loadConfigFromResource(String fileName) throws IOException {
    InputStream fileStream = StateStore.class.getClassLoader().getResourceAsStream(fileName);
    return IOUtils.toString(fileStream, StandardCharsets.UTF_8);
  }

  /** Helper Functions */
  public static Function<StatementModel, StatementModel> createStatement(
      StateStore stateStore, String datasourceName) {
    return (st) ->
        stateStore.create(
            st, StatementModel::copy, DATASOURCE_TO_REQUEST_INDEX.apply(datasourceName));
  }

  public static Function<String, Optional<StatementModel>> getStatement(
      StateStore stateStore, String datasourceName) {
    return (docId) ->
        stateStore.get(
            docId, StatementModel::fromXContent, DATASOURCE_TO_REQUEST_INDEX.apply(datasourceName));
  }

  public static BiFunction<StatementModel, StatementState, StatementModel> updateStatementState(
      StateStore stateStore, String datasourceName) {
    return (old, state) ->
        stateStore.updateState(
            old,
            state,
            StatementModel::copyWithState,
            DATASOURCE_TO_REQUEST_INDEX.apply(datasourceName));
  }

  public static Function<SessionModel, SessionModel> createSession(
      StateStore stateStore, String datasourceName) {
    return (session) ->
        stateStore.create(
            session, SessionModel::of, DATASOURCE_TO_REQUEST_INDEX.apply(datasourceName));
  }

  public static Function<String, Optional<SessionModel>> getSession(
      StateStore stateStore, String datasourceName) {
    return (docId) ->
        stateStore.get(
            docId, SessionModel::fromXContent, DATASOURCE_TO_REQUEST_INDEX.apply(datasourceName));
  }

  public static BiFunction<SessionModel, SessionState, SessionModel> updateSessionState(
      StateStore stateStore, String datasourceName) {
    return (old, state) ->
        stateStore.updateState(
            old,
            state,
            SessionModel::copyWithState,
            DATASOURCE_TO_REQUEST_INDEX.apply(datasourceName));
  }

  public static Function<AsyncQueryJobMetadata, AsyncQueryJobMetadata> createJobMetaData(
      StateStore stateStore, String datasourceName) {
    return (jobMetadata) ->
        stateStore.create(
            jobMetadata,
            AsyncQueryJobMetadata::copy,
            DATASOURCE_TO_REQUEST_INDEX.apply(datasourceName));
  }

  public static Function<String, Optional<AsyncQueryJobMetadata>> getJobMetaData(
      StateStore stateStore, String datasourceName) {
    return (docId) ->
        stateStore.get(
            docId,
            AsyncQueryJobMetadata::fromXContent,
            DATASOURCE_TO_REQUEST_INDEX.apply(datasourceName));
  }
}
