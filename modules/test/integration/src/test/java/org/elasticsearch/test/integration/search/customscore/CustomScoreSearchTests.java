/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.integration.search.customscore;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.test.integration.AbstractNodesTests;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Arrays;

import static org.elasticsearch.client.Requests.*;
import static org.elasticsearch.common.xcontent.XContentFactory.*;
import static org.elasticsearch.index.query.FilterBuilders.*;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.search.builder.SearchSourceBuilder.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

/**
 * @author kimchy (shay.banon)
 */
@Test
public class CustomScoreSearchTests extends AbstractNodesTests {

    private Client client;

    @BeforeMethod public void createNodes() throws Exception {
        startNode("server1");
        client = getClient();
    }

    @AfterMethod public void closeNodes() {
        client.close();
        closeAllNodes();
    }

    protected Client getClient() {
        return client("server1");
    }

    @Test public void testCustomScriptBoost() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().create(createIndexRequest("test")).actionGet();
        client.index(indexRequest("test").type("type1").id("1")
                .source(jsonBuilder().startObject().field("test", "value beck").field("num1", 1.0f).endObject())).actionGet();
        client.index(indexRequest("test").type("type1").id("2")
                .source(jsonBuilder().startObject().field("test", "value check").field("num1", 2.0f).endObject())).actionGet();
        client.admin().indices().refresh(refreshRequest()).actionGet();

        logger.info("--- QUERY_THEN_FETCH");

        logger.info("running doc['num1'].value");
        SearchResponse response = client.search(searchRequest()
                .searchType(SearchType.QUERY_THEN_FETCH)
                .source(searchSource().explain(true).query(customScoreQuery(termQuery("test", "value")).script("doc['num1'].value")))
        ).actionGet();

        assertThat(response.hits().totalHits(), equalTo(2l));
        logger.info("Hit[0] {} Explanation {}", response.hits().getAt(0).id(), response.hits().getAt(0).explanation());
        logger.info("Hit[1] {} Explanation {}", response.hits().getAt(1).id(), response.hits().getAt(1).explanation());
        assertThat(response.hits().getAt(0).id(), equalTo("2"));
        assertThat(response.hits().getAt(1).id(), equalTo("1"));

        logger.info("running -doc['num1'].value");
        response = client.search(searchRequest()
                .searchType(SearchType.QUERY_THEN_FETCH)
                .source(searchSource().explain(true).query(customScoreQuery(termQuery("test", "value")).script("-doc['num1'].value")))
        ).actionGet();

        assertThat(response.hits().totalHits(), equalTo(2l));
        logger.info("Hit[0] {} Explanation {}", response.hits().getAt(0).id(), response.hits().getAt(0).explanation());
        logger.info("Hit[1] {} Explanation {}", response.hits().getAt(1).id(), response.hits().getAt(1).explanation());
        assertThat(response.hits().getAt(0).id(), equalTo("1"));
        assertThat(response.hits().getAt(1).id(), equalTo("2"));


        logger.info("running pow(doc['num1'].value, 2)");
        response = client.search(searchRequest()
                .searchType(SearchType.QUERY_THEN_FETCH)
                .source(searchSource().explain(true).query(customScoreQuery(termQuery("test", "value")).script("pow(doc['num1'].value, 2)")))
        ).actionGet();

        assertThat(response.hits().totalHits(), equalTo(2l));
        logger.info("Hit[0] {} Explanation {}", response.hits().getAt(0).id(), response.hits().getAt(0).explanation());
        logger.info("Hit[1] {} Explanation {}", response.hits().getAt(1).id(), response.hits().getAt(1).explanation());
        assertThat(response.hits().getAt(0).id(), equalTo("2"));
        assertThat(response.hits().getAt(1).id(), equalTo("1"));

        logger.info("running max(doc['num1'].value, 1)");
        response = client.search(searchRequest()
                .searchType(SearchType.QUERY_THEN_FETCH)
                .source(searchSource().explain(true).query(customScoreQuery(termQuery("test", "value")).script("max(doc['num1'].value, 1d)")))
        ).actionGet();

        assertThat(response.hits().totalHits(), equalTo(2l));
        logger.info("Hit[0] {} Explanation {}", response.hits().getAt(0).id(), response.hits().getAt(0).explanation());
        logger.info("Hit[1] {} Explanation {}", response.hits().getAt(1).id(), response.hits().getAt(1).explanation());
        assertThat(response.hits().getAt(0).id(), equalTo("2"));
        assertThat(response.hits().getAt(1).id(), equalTo("1"));

        logger.info("running doc['num1'].value * _score");
        response = client.search(searchRequest()
                .searchType(SearchType.QUERY_THEN_FETCH)
                .source(searchSource().explain(true).query(customScoreQuery(termQuery("test", "value")).script("doc['num1'].value * _score")))
        ).actionGet();

        assertThat(response.hits().totalHits(), equalTo(2l));
        logger.info("Hit[0] {} Explanation {}", response.hits().getAt(0).id(), response.hits().getAt(0).explanation());
        logger.info("Hit[1] {} Explanation {}", response.hits().getAt(1).id(), response.hits().getAt(1).explanation());
        assertThat(response.hits().getAt(0).id(), equalTo("2"));
        assertThat(response.hits().getAt(1).id(), equalTo("1"));

        logger.info("running param1 * param2 * _score");
        response = client.search(searchRequest()
                .searchType(SearchType.QUERY_THEN_FETCH)
                .source(searchSource().explain(true).query(customScoreQuery(termQuery("test", "value")).script("param1 * param2 * _score").param("param1", 2).param("param2", 2)))
        ).actionGet();

        assertThat(response.hits().totalHits(), equalTo(2l));
        logger.info("Hit[0] {} Explanation {}", response.hits().getAt(0).id(), response.hits().getAt(0).explanation());
        logger.info("Hit[1] {} Explanation {}", response.hits().getAt(1).id(), response.hits().getAt(1).explanation());
        assertThat(response.hits().getAt(0).id(), equalTo("1"));
        assertThat(response.hits().getAt(1).id(), equalTo("2"));
    }

    @Test public void testCustomFiltersScore() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.prepareIndex("test", "type", "1").setSource("field", "value1", "color", "red").execute().actionGet();
        client.prepareIndex("test", "type", "2").setSource("field", "value2", "color", "blue").execute().actionGet();
        client.prepareIndex("test", "type", "3").setSource("field", "value3", "color", "red").execute().actionGet();
        client.prepareIndex("test", "type", "4").setSource("field", "value4", "color", "blue").execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch("test")
                .setQuery(customFiltersScoreQuery(matchAllQuery())
                        .add(termFilter("field", "value4"), "_score * 2")
                        .add(termFilter("field", "value2"), "_score * 3"))
                .setExplain(true)
                .execute().actionGet();

        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));

        assertThat(searchResponse.hits().totalHits(), equalTo(4l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("2"));
        assertThat(searchResponse.hits().getAt(0).score(), equalTo(3.0f));
        logger.info("--> Hit[0] {} Explanation {}", searchResponse.hits().getAt(0).id(), searchResponse.hits().getAt(0).explanation());
        assertThat(searchResponse.hits().getAt(1).id(), equalTo("4"));
        assertThat(searchResponse.hits().getAt(1).score(), equalTo(2.0f));
        assertThat(searchResponse.hits().getAt(2).id(), anyOf(equalTo("1"), equalTo("3")));
        assertThat(searchResponse.hits().getAt(2).score(), equalTo(1.0f));
        assertThat(searchResponse.hits().getAt(3).id(), anyOf(equalTo("1"), equalTo("3")));
        assertThat(searchResponse.hits().getAt(3).score(), equalTo(1.0f));

        searchResponse = client.prepareSearch("test")
                .setQuery(customFiltersScoreQuery(matchAllQuery())
                        .add(termFilter("field", "value4"), 2)
                        .add(termFilter("field", "value2"), 3))
                .setExplain(true)
                .execute().actionGet();

        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));

        assertThat(searchResponse.hits().totalHits(), equalTo(4l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("2"));
        assertThat(searchResponse.hits().getAt(0).score(), equalTo(3.0f));
        logger.info("--> Hit[0] {} Explanation {}", searchResponse.hits().getAt(0).id(), searchResponse.hits().getAt(0).explanation());
        assertThat(searchResponse.hits().getAt(1).id(), equalTo("4"));
        assertThat(searchResponse.hits().getAt(1).score(), equalTo(2.0f));
        assertThat(searchResponse.hits().getAt(2).id(), anyOf(equalTo("1"), equalTo("3")));
        assertThat(searchResponse.hits().getAt(2).score(), equalTo(1.0f));
        assertThat(searchResponse.hits().getAt(3).id(), anyOf(equalTo("1"), equalTo("3")));
        assertThat(searchResponse.hits().getAt(3).score(), equalTo(1.0f));

        searchResponse = client.prepareSearch("test")
                .setQuery(customFiltersScoreQuery(matchAllQuery()).scoreMode("total")
                        .add(termFilter("field", "value4"), 2)
                        .add(termFilter("field", "value1"), 3)
                        .add(termFilter("color", "red"), 5))
                .setExplain(true)
                .execute().actionGet();

        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(4l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("1"));
        assertThat(searchResponse.hits().getAt(0).score(), equalTo(8.0f));
        logger.info("--> Hit[0] {} Explanation {}", searchResponse.hits().getAt(0).id(), searchResponse.hits().getAt(0).explanation());

        searchResponse = client.prepareSearch("test")
                .setQuery(customFiltersScoreQuery(matchAllQuery()).scoreMode("max")
                        .add(termFilter("field", "value4"), 2)
                        .add(termFilter("field", "value1"), 3)
                        .add(termFilter("color", "red"), 5))
                .setExplain(true)
                .execute().actionGet();

        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(4l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("1"));
        assertThat(searchResponse.hits().getAt(0).score(), equalTo(5.0f));
        logger.info("--> Hit[0] {} Explanation {}", searchResponse.hits().getAt(0).id(), searchResponse.hits().getAt(0).explanation());

        searchResponse = client.prepareSearch("test")
                .setQuery(customFiltersScoreQuery(matchAllQuery()).scoreMode("avg")
                        .add(termFilter("field", "value4"), 2)
                        .add(termFilter("field", "value1"), 3)
                        .add(termFilter("color", "red"), 5))
                .setExplain(true)
                .execute().actionGet();

        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(4l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("3"));
        assertThat(searchResponse.hits().getAt(0).score(), equalTo(5.0f));
        logger.info("--> Hit[0] {} Explanation {}", searchResponse.hits().getAt(0).id(), searchResponse.hits().getAt(0).explanation());
        assertThat(searchResponse.hits().getAt(1).id(), equalTo("1"));
        assertThat(searchResponse.hits().getAt(1).score(), equalTo(4.0f));
        logger.info("--> Hit[1] {} Explanation {}", searchResponse.hits().getAt(1).id(), searchResponse.hits().getAt(1).explanation());

        searchResponse = client.prepareSearch("test")
                .setQuery(customFiltersScoreQuery(matchAllQuery()).scoreMode("min")
                        .add(termFilter("field", "value4"), 2)
                        .add(termFilter("field", "value1"), 3)
                        .add(termFilter("color", "red"), 5))
                .setExplain(true)
                .execute().actionGet();

        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(4l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("3"));
        assertThat(searchResponse.hits().getAt(0).score(), equalTo(5.0f));
        logger.info("--> Hit[0] {} Explanation {}", searchResponse.hits().getAt(0).id(), searchResponse.hits().getAt(0).explanation());
        assertThat(searchResponse.hits().getAt(1).id(), equalTo("1"));
        assertThat(searchResponse.hits().getAt(1).score(), equalTo(3.0f));
        assertThat(searchResponse.hits().getAt(2).id(), equalTo("4"));
        assertThat(searchResponse.hits().getAt(2).score(), equalTo(2.0f));
        assertThat(searchResponse.hits().getAt(3).id(), equalTo("2"));
        assertThat(searchResponse.hits().getAt(3).score(), equalTo(1.0f));

        searchResponse = client.prepareSearch("test")
                .setQuery(customFiltersScoreQuery(matchAllQuery()).scoreMode("multiply")
                        .add(termFilter("field", "value4"), 2)
                        .add(termFilter("field", "value1"), 3)
                        .add(termFilter("color", "red"), 5))
                .setExplain(true)
                .execute().actionGet();

        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(4l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("1"));
        assertThat(searchResponse.hits().getAt(0).score(), equalTo(15.0f));
        logger.info("--> Hit[0] {} Explanation {}", searchResponse.hits().getAt(0).id(), searchResponse.hits().getAt(0).explanation());
        assertThat(searchResponse.hits().getAt(1).id(), equalTo("3"));
        assertThat(searchResponse.hits().getAt(1).score(), equalTo(5.0f));
        assertThat(searchResponse.hits().getAt(2).id(), equalTo("4"));
        assertThat(searchResponse.hits().getAt(2).score(), equalTo(2.0f));
        assertThat(searchResponse.hits().getAt(3).id(), equalTo("2"));
        assertThat(searchResponse.hits().getAt(3).score(), equalTo(1.0f));
    }
}