/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.datastreams;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.DataStreamTestHelper;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.MetadataCreateIndexService;
import org.elasticsearch.cluster.metadata.MetadataIndexTemplateService;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettingProviders;
import org.elasticsearch.indices.EmptySystemIndices;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.ShardLimitValidator;
import org.elasticsearch.test.ESSingleNodeTestCase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.cluster.metadata.DataStreamTestHelper.generateTsdbMapping;
import static org.elasticsearch.common.settings.Settings.builder;
import static org.elasticsearch.indices.ShardLimitValidator.SETTING_CLUSTER_MAX_SHARDS_PER_NODE;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Variant of MetadataIndexTemplateServiceTests in server module, but with {@link DataStreamIndexSettingsProvider}.
 */
public class MetadataIndexTemplateServiceTests extends ESSingleNodeTestCase {

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/86333")
    public void testValidateTsdbDataStreamsReferringTsdbTemplate() throws Exception {
        var state = ClusterState.EMPTY_STATE;
        final var service = getMetadataIndexTemplateService();
        var template = new ComposableIndexTemplate(
            Collections.singletonList("logs-*-*"),
            new Template(builder().put("index.routing_path", "uid").build(), new CompressedXContent(generateTsdbMapping()), null),
            null,
            100L,
            null,
            null,
            new ComposableIndexTemplate.DataStreamTemplate(false, false),
            null
        );
        state = service.addIndexTemplateV2(state, false, "logs", template);

        var now = Instant.now();
        var mBuilder = Metadata.builder(state.getMetadata());
        DataStreamTestHelper.getClusterStateWithDataStream(
            mBuilder,
            "unreferenced",
            List.of(Tuple.tuple(now.minus(2, ChronoUnit.HOURS), now))
        );
        DataStreamTestHelper.getClusterStateWithDataStream(
            mBuilder,
            "logs-mysql-default",
            List.of(Tuple.tuple(now.minus(2, ChronoUnit.HOURS), now))
        );
        var stateWithDS = ClusterState.builder(state).metadata(mBuilder).build();

        var e = expectThrows(IllegalArgumentException.class, () -> {
            ComposableIndexTemplate nonDSTemplate = new ComposableIndexTemplate(
                Collections.singletonList("logs-*-*"),
                null,
                null,
                100L,
                null,
                null,
                new ComposableIndexTemplate.DataStreamTemplate(false, false),
                null
            );
            service.addIndexTemplateV2(stateWithDS, false, "logs", nonDSTemplate);
        });

        assertThat(
            e.getMessage(),
            containsString(
                "would cause tsdb data streams [logs-mysql-default] to no longer match a data stream template with a time_series index_mode"
            )
        );
    }

    private MetadataIndexTemplateService getMetadataIndexTemplateService() {
        var indicesService = getInstanceFromNode(IndicesService.class);
        var clusterService = getInstanceFromNode(ClusterService.class);
        var indexSettingProviders = new IndexSettingProviders(Set.of(new DataStreamIndexSettingsProvider()));
        var createIndexService = new MetadataCreateIndexService(
            Settings.EMPTY,
            clusterService,
            indicesService,
            null,
            createTestShardLimitService(randomIntBetween(1, 1000)),
            new Environment(builder().put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString()).build(), null),
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            null,
            xContentRegistry(),
            EmptySystemIndices.INSTANCE,
            true,
            indexSettingProviders
        );
        return new MetadataIndexTemplateService(
            clusterService,
            createIndexService,
            indicesService,
            new IndexScopedSettings(Settings.EMPTY, IndexScopedSettings.BUILT_IN_INDEX_SETTINGS),
            xContentRegistry(),
            EmptySystemIndices.INSTANCE,
            indexSettingProviders
        );
    }

    public static ShardLimitValidator createTestShardLimitService(int maxShardsPerNode) {
        // Use a mocked clusterService - for unit tests we won't be updating the setting anyway.
        ClusterService clusterService = mock(ClusterService.class);
        Settings limitOnlySettings = Settings.builder().put(SETTING_CLUSTER_MAX_SHARDS_PER_NODE.getKey(), maxShardsPerNode).build();
        when(clusterService.getClusterSettings()).thenReturn(
            new ClusterSettings(limitOnlySettings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)
        );

        return new ShardLimitValidator(limitOnlySettings, clusterService);
    }

}
