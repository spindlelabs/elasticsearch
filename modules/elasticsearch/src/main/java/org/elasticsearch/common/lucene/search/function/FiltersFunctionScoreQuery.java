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

package org.elasticsearch.common.lucene.search.function;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.ComplexExplanation;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.search.Similarity;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.ToStringUtils;
import org.elasticsearch.common.lucene.docset.DocSet;
import org.elasticsearch.common.lucene.docset.DocSets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

/**
 * A query that allows for a pluggable boost function / filter. If it matches the filter, it will
 * be boosted by the formula.
 *
 * @author kimchy (shay.banon)
 */
public class FiltersFunctionScoreQuery extends Query {

    public static class FilterFunction {
        public final Filter filter;
        public final ScoreFunction function;

        public FilterFunction(Filter filter, ScoreFunction function) {
            this.filter = filter;
            this.function = function;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            FilterFunction that = (FilterFunction) o;

            if (filter != null ? !filter.equals(that.filter) : that.filter != null) return false;
            if (function != null ? !function.equals(that.function) : that.function != null) return false;

            return true;
        }

        @Override public int hashCode() {
            int result = filter != null ? filter.hashCode() : 0;
            result = 31 * result + (function != null ? function.hashCode() : 0);
            return result;
        }
    }

    public static enum ScoreMode {First, Avg, Max, Total, Min, Multiply}

    Query subQuery;
    final FilterFunction[] filterFunctions;
    final ScoreMode scoreMode;
    DocSet[] docSets;

    public FiltersFunctionScoreQuery(Query subQuery, ScoreMode scoreMode, FilterFunction[] filterFunctions) {
        this.subQuery = subQuery;
        this.scoreMode = scoreMode;
        this.filterFunctions = filterFunctions;
        this.docSets = new DocSet[filterFunctions.length];
    }

    public Query getSubQuery() {
        return subQuery;
    }

    public FilterFunction[] getFilterFunctions() {
        return filterFunctions;
    }

    @Override
    public Query rewrite(IndexReader reader) throws IOException {
        Query newQ = subQuery.rewrite(reader);
        if (newQ == subQuery) return this;
        FiltersFunctionScoreQuery bq = (FiltersFunctionScoreQuery) this.clone();
        bq.subQuery = newQ;
        return bq;
    }

    @Override
    public void extractTerms(Set<Term> terms) {
        subQuery.extractTerms(terms);
    }

    @Override
    public Weight createWeight(Searcher searcher) throws IOException {
        return new CustomBoostFactorWeight(searcher);
    }

    class CustomBoostFactorWeight extends Weight {
        Searcher searcher;
        Weight subQueryWeight;

        public CustomBoostFactorWeight(Searcher searcher) throws IOException {
            this.searcher = searcher;
            this.subQueryWeight = subQuery.weight(searcher);
        }

        public Query getQuery() {
            return FiltersFunctionScoreQuery.this;
        }

        public float getValue() {
            return getBoost();
        }

        @Override
        public float sumOfSquaredWeights() throws IOException {
            float sum = subQueryWeight.sumOfSquaredWeights();
            sum *= getBoost() * getBoost();
            return sum;
        }

        @Override
        public void normalize(float norm) {
            norm *= getBoost();
            subQueryWeight.normalize(norm);
        }

        @Override
        public Scorer scorer(IndexReader reader, boolean scoreDocsInOrder, boolean topScorer) throws IOException {
            Scorer subQueryScorer = subQueryWeight.scorer(reader, scoreDocsInOrder, false);
            if (subQueryScorer == null) {
                return null;
            }
            for (int i = 0; i < filterFunctions.length; i++) {
                FilterFunction filterFunction = filterFunctions[i];
                filterFunction.function.setNextReader(reader);
                docSets[i] = DocSets.convert(reader, filterFunction.filter.getDocIdSet(reader));
            }
            return new CustomBoostFactorScorer(getSimilarity(searcher), this, subQueryScorer, scoreMode, filterFunctions, docSets);
        }

        @Override
        public Explanation explain(IndexReader reader, int doc) throws IOException {
            Explanation subQueryExpl = subQueryWeight.explain(reader, doc);
            if (!subQueryExpl.isMatch()) {
                return subQueryExpl;
            }

            if (scoreMode == ScoreMode.First) {
                for (FilterFunction filterFunction : filterFunctions) {
                    DocSet docSet = DocSets.convert(reader, filterFunction.filter.getDocIdSet(reader));
                    if (docSet.get(doc)) {
                        filterFunction.function.setNextReader(reader);
                        Explanation functionExplanation = filterFunction.function.explain(doc, subQueryExpl);
                        float sc = getValue() * functionExplanation.getValue();
                        Explanation res = new ComplexExplanation(true, sc, "custom score, product of:");
                        res.addDetail(new Explanation(1.0f, "match filter: " + filterFunction.filter.toString()));
                        res.addDetail(functionExplanation);
                        res.addDetail(new Explanation(getValue(), "queryBoost"));
                        return res;
                    }
                }
            } else {
                int count = 0;
                float total = 0;
                float multiply = 1;
                float max = Float.NEGATIVE_INFINITY;
                float min = Float.POSITIVE_INFINITY;
                ArrayList<Explanation> filtersExplanations = new ArrayList<Explanation>();
                for (FilterFunction filterFunction : filterFunctions) {
                    DocSet docSet = DocSets.convert(reader, filterFunction.filter.getDocIdSet(reader));
                    if (docSet.get(doc)) {
                        filterFunction.function.setNextReader(reader);
                        Explanation functionExplanation = filterFunction.function.explain(doc, subQueryExpl);
                        float sc = functionExplanation.getValue();
                        count++;
                        total += sc;
                        multiply *= sc;
                        max = Math.max(sc, max);
                        min = Math.min(sc, min);
                        Explanation res = new ComplexExplanation(true, sc, "custom score, product of:");
                        res.addDetail(new Explanation(1.0f, "match filter: " + filterFunction.filter.toString()));
                        res.addDetail(functionExplanation);
                        res.addDetail(new Explanation(getValue(), "queryBoost"));
                        filtersExplanations.add(res);
                    }
                }
                if (count > 0) {
                    float sc = 0;
                    switch (scoreMode) {
                        case Avg:
                            sc = total / count;
                            break;
                        case Max:
                            sc = max;
                            break;
                        case Min:
                            sc = min;
                            break;
                        case Total:
                            sc = total;
                            break;
                        case Multiply:
                            sc = multiply;
                            break;
                    }
                    sc *= getValue();
                    Explanation res = new ComplexExplanation(true, sc, "custom score, score mode [" + scoreMode.toString().toLowerCase() + "]");
                    for (Explanation explanation : filtersExplanations) {
                        res.addDetail(explanation);
                    }
                    return res;
                }
            }

            float sc = getValue() * subQueryExpl.getValue();
            Explanation res = new ComplexExplanation(true, sc, "custom score, no filter match, product of:");
            res.addDetail(subQueryExpl);
            res.addDetail(new Explanation(getValue(), "queryBoost"));
            return res;
        }
    }


    static class CustomBoostFactorScorer extends Scorer {
        private final float subQueryWeight;
        private final Scorer scorer;
        private final FilterFunction[] filterFunctions;
        private final ScoreMode scoreMode;
        private final DocSet[] docSets;

        private CustomBoostFactorScorer(Similarity similarity, CustomBoostFactorWeight w, Scorer scorer,
                                        ScoreMode scoreMode, FilterFunction[] filterFunctions, DocSet[] docSets) throws IOException {
            super(similarity);
            this.subQueryWeight = w.getValue();
            this.scorer = scorer;
            this.scoreMode = scoreMode;
            this.filterFunctions = filterFunctions;
            this.docSets = docSets;
        }

        @Override
        public int docID() {
            return scorer.docID();
        }

        @Override
        public int advance(int target) throws IOException {
            return scorer.advance(target);
        }

        @Override
        public int nextDoc() throws IOException {
            return scorer.nextDoc();
        }

        @Override
        public float score() throws IOException {
            int docId = scorer.docID();
            float score = scorer.score();
            if (scoreMode == ScoreMode.First) {
                for (int i = 0; i < filterFunctions.length; i++) {
                    if (docSets[i].get(docId)) {
                        return subQueryWeight * filterFunctions[i].function.score(docId, score);
                    }
                }
            } else if (scoreMode == ScoreMode.Max) {
                float maxScore = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < filterFunctions.length; i++) {
                    if (docSets[i].get(docId)) {
                        maxScore = Math.max(filterFunctions[i].function.score(docId, score), maxScore);
                    }
                }
                if (maxScore != Float.NEGATIVE_INFINITY) {
                    score = maxScore;
                }
            } else if (scoreMode == ScoreMode.Min) {
                float minScore = Float.POSITIVE_INFINITY;
                for (int i = 0; i < filterFunctions.length; i++) {
                    if (docSets[i].get(docId)) {
                        minScore = Math.min(filterFunctions[i].function.score(docId, score), minScore);
                    }
                }
                if (minScore != Float.POSITIVE_INFINITY) {
                    score = minScore;
                }
            } else { // Avg / Total
                float totalScore = 0.0f;
                float multiplicativeScore = 1.0f;
                int count = 0;
                for (int i = 0; i < filterFunctions.length; i++) {
                    if (docSets[i].get(docId)) {
                        float tempScore = filterFunctions[i].function.score(docId, score);
                        totalScore += tempScore;
                        multiplicativeScore *= tempScore;
                        count++;
                    }
                }
                if (count != 0) {
                    score = totalScore;
                    if (scoreMode == ScoreMode.Avg) {
                        score /= count;
                    }
                    else if (scoreMode == ScoreMode.Multiply) {
                        score = multiplicativeScore;
                    }
                }
            }
            return subQueryWeight * score;
        }
    }


    public String toString(String field) {
        StringBuilder sb = new StringBuilder();
        sb.append("custom score (").append(subQuery.toString(field)).append(", functions: [");
        for (FilterFunction filterFunction : filterFunctions) {
            sb.append("{filter(").append(filterFunction.filter).append("), function [").append(filterFunction.function).append("]}");
        }
        sb.append("])");
        sb.append(ToStringUtils.boost(getBoost()));
        return sb.toString();
    }

    public boolean equals(Object o) {
        if (getClass() != o.getClass()) return false;
        FiltersFunctionScoreQuery other = (FiltersFunctionScoreQuery) o;
        if (this.getBoost() != other.getBoost())
            return false;
        if (!this.subQuery.equals(other.subQuery)) {
            return false;
        }
        return Arrays.equals(this.filterFunctions, other.filterFunctions);
    }

    public int hashCode() {
        return subQuery.hashCode() + 31 * Arrays.hashCode(filterFunctions) ^ Float.floatToIntBits(getBoost());
    }
}

