package com.bank.intuitivesearch.index;

import com.bank.intuitivesearch.model.Feature;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.BooleanSimilarity;
import org.apache.lucene.search.similarities.PerFieldSimilarityWrapper;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Stage-1 feature index: Apache Lucene, in process, in memory.
 *
 * <p>This is where the three retrieval channels that used to be Postgres
 * extensions now live, one field each:
 *
 * <table>
 *   <tr><th>field</th><th>was</th><th>now</th></tr>
 *   <tr><td>{@code text}</td><td>{@code tsvector} + {@code ts_rank_cd}</td>
 *       <td>English-stemmed {@code TextField}, BM25, prefix terms for typeahead</td></tr>
 *   <tr><td>{@code ngram}</td><td>{@code pg_trgm} similarity</td>
 *       <td>one term per padded trigram, boolean-scored so a hit's score is
 *           the number of query trigrams it contains</td></tr>
 *   <tr><td>{@code embedding}</td><td>{@code pgvector} HNSW</td>
 *       <td>{@link KnnFloatVectorField}, cosine, HNSW</td></tr>
 * </table>
 *
 * <p>The index is a derived cache, never a store: it is rebuilt from the
 * {@code features} table whenever the registry changes and holds nothing
 * that cannot be rebuilt in milliseconds. Postgres remains the system of
 * record. A rebuild produces a whole new generation and swaps it in
 * atomically; searches in flight keep the generation they started on.
 *
 * <p>Scores are mapped onto the same 0..1 scales the SQL channels produced,
 * so the merge weights in {@code HybridSearchService} keep their meaning:
 * BM25 goes through {@code s / (1 + s)} (the normalisation {@code ts_rank_cd}
 * applied with flag 32), trigrams reproduce {@code word_similarity} and
 * {@code similarity}, and Lucene's {@code (1 + cos) / 2} is turned back into
 * plain cosine.
 */
@Component
public class FeatureIndex {

    private static final Logger log = LoggerFactory.getLogger(FeatureIndex.class);

    static final String F_ID = "feature_id";
    static final String F_TEXT = "text";
    static final String F_NGRAM = "ngram";
    static final String F_NGRAM_COUNT = "ngram_count";
    static final String F_VECTOR = "embedding";

    /**
     * pg_trgm's defaults: a fuzzy hit only counts when the query's trigrams
     * are mostly inside the document ({@code word_similarity_threshold}) or
     * the two sets mostly overlap ({@code similarity_threshold}). Without the
     * floor, every feature sharing a stray "an " with the query would score.
     */
    static final double WORD_SIMILARITY_THRESHOLD = 0.6;
    static final double SIMILARITY_THRESHOLD = 0.3;
    /** Shorter fragments than this match too much to be evidence of intent. */
    private static final int MIN_PREFIX = 3;

    private final Analyzer analyzer;
    private final Similarity similarity;
    private final AtomicReference<Generation> current = new AtomicReference<>();

    public FeatureIndex() {
        Map<String, Analyzer> perField = new HashMap<>();
        perField.put(F_ID, new KeywordAnalyzer());
        perField.put(F_NGRAM, new KeywordAnalyzer());
        this.analyzer = new PerFieldAnalyzerWrapper(new EnglishAnalyzer(), perField);
        this.similarity = new PerFieldSimilarityWrapper() {
            private final Similarity bm25 = new BM25Similarity();
            private final Similarity bool = new BooleanSimilarity();

            @Override
            public Similarity get(String name) {
                return F_NGRAM.equals(name) ? bool : bm25;
            }
        };
    }

    /** A feature plus the vector to index it under, if one is available. */
    public record IndexDocument(Feature feature, float[] vector) {}

    /**
     * Builds a new generation from scratch and swaps it in. Callers pass the
     * stats they want reported for it; the index itself only counts.
     */
    public void rebuild(List<IndexDocument> documents, IndexStats stats) {
        long started = System.nanoTime();
        Directory directory = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                .setSimilarity(similarity);
        int withVector = 0;
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (IndexDocument item : documents) {
                writer.addDocument(toDocument(item));
                if (item.vector() != null) {
                    withVector++;
                }
            }
            writer.commit();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to build feature index", e);
        }

        DirectoryReader reader;
        try {
            reader = DirectoryReader.open(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open feature index", e);
        }
        IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(similarity);

        IndexStats reported = new IndexStats(documents.size(), withVector, stats.dimensions(),
                stats.embeddingModel(), stats.builtAt(), stats.buildMs(), stats.embeddedInBuild(),
                stats.lastError());
        Generation previous = current.getAndSet(new Generation(directory, reader, searcher, reported));
        if (previous != null) {
            previous.release();
        }
        log.debug("Feature index rebuilt: {} documents, {} with vectors, {}ms",
                documents.size(), withVector, (System.nanoTime() - started) / 1_000_000);
    }

    private Document toDocument(IndexDocument item) {
        Feature feature = item.feature();
        Document doc = new Document();
        doc.add(new StringField(F_ID, feature.featureId(), Field.Store.YES));
        doc.add(new TextField(F_TEXT, FeatureDocument.fullText(feature), Field.Store.NO));
        Set<String> trigrams = Trigrams.of(FeatureDocument.matchText(feature));
        for (String trigram : trigrams) {
            doc.add(new StringField(F_NGRAM, trigram, Field.Store.NO));
        }
        doc.add(new StoredField(F_NGRAM_COUNT, trigrams.size()));
        if (item.vector() != null) {
            doc.add(new KnnFloatVectorField(F_VECTOR, item.vector(), VectorSimilarityFunction.COSINE));
        }
        return doc;
    }

    /**
     * Lexical channel: BM25 over stemmed text with prefix matching, plus
     * trigram similarity for typos and abbreviations. Returns at most
     * {@code limit} features, ordered by the better of the two — the same
     * shape the SQL channel had.
     */
    public List<ChannelHit> lexicalSearch(String normalisedQuery, int limit) {
        Generation gen = acquire();
        if (gen == null) {
            return List.of();
        }
        try {
            Map<String, double[]> scores = new HashMap<>();
            int fetch = Math.min(gen.reader.numDocs(), Math.max(limit * 10, 100));
            if (fetch == 0) {
                return List.of();
            }

            Query keyword = keywordQuery(normalisedQuery);
            if (keyword != null) {
                for (ScoreDoc hit : gen.searcher.search(keyword, fetch).scoreDocs) {
                    Document doc = gen.searcher.storedFields().document(hit.doc);
                    // ts_rank_cd(..., 32) normalised as rank / (rank + 1); BM25
                    // is unbounded in the same way, so the same mapping keeps
                    // the channel on a comparable scale.
                    double score = hit.score / (1 + hit.score);
                    scores.computeIfAbsent(doc.get(F_ID), k -> new double[2])[0] = score;
                }
            }

            Set<String> queryTrigrams = Trigrams.of(normalisedQuery);
            if (!queryTrigrams.isEmpty()) {
                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                for (String trigram : queryTrigrams) {
                    builder.add(new TermQuery(new Term(F_NGRAM, trigram)), BooleanClause.Occur.SHOULD);
                }
                for (ScoreDoc hit : gen.searcher.search(builder.build(), fetch).scoreDocs) {
                    Document doc = gen.searcher.storedFields().document(hit.doc);
                    // BooleanSimilarity scores each matched clause as exactly
                    // its boost (1.0), so the score *is* the matched count.
                    double matched = Math.round(hit.score);
                    int docCount = doc.getField(F_NGRAM_COUNT).numericValue().intValue();
                    double wordSimilarity = matched / queryTrigrams.size();
                    double similarity = matched / (queryTrigrams.size() + docCount - matched);
                    if (wordSimilarity < WORD_SIMILARITY_THRESHOLD && similarity < SIMILARITY_THRESHOLD) {
                        continue;
                    }
                    scores.computeIfAbsent(doc.get(F_ID), k -> new double[2])[1] =
                            Math.max(wordSimilarity, similarity);
                }
            }

            List<ChannelHit> hits = new ArrayList<>(scores.size());
            scores.forEach((id, s) -> hits.add(new ChannelHit(id, s[0], s[1], 0.0)));
            hits.sort(Comparator
                    .<ChannelHit>comparingDouble(h -> Math.max(h.keywordScore(), h.trigramScore()))
                    .reversed()
                    .thenComparing(ChannelHit::featureId));
            return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            gen.release();
        }
    }

    /** Vector channel: cosine similarity over the HNSW graph. */
    public List<ChannelHit> vectorSearch(float[] queryEmbedding, int limit) {
        Generation gen = acquire();
        if (gen == null || gen.stats.withVector() == 0) {
            if (gen != null) {
                gen.release();
            }
            return List.of();
        }
        try {
            TopDocs top = gen.searcher.search(new KnnFloatVectorQuery(F_VECTOR, queryEmbedding, limit), limit);
            List<ChannelHit> hits = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                Document doc = gen.searcher.storedFields().document(hit.doc);
                // Lucene reports COSINE as (1 + cos) / 2 so it is never
                // negative; undo that to get the cosine the weights expect.
                double cosine = 2.0 * hit.score - 1.0;
                hits.add(new ChannelHit(doc.get(F_ID), 0.0, 0.0, cosine));
            }
            return hits;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            gen.release();
        }
    }

    public IndexStats stats() {
        Generation gen = current.get();
        return gen == null ? null : gen.stats;
    }

    public boolean isReady() {
        return current.get() != null;
    }

    /**
     * Stemmed terms for every word, plus a prefix clause for words long
     * enough to mean something — that is what makes "tran" find Transfer
     * while the user is still typing. All clauses are optional: any one hit
     * scores, more hits score higher.
     */
    private Query keywordQuery(String normalisedQuery) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        boolean any = false;
        for (String token : analyse(normalisedQuery)) {
            builder.add(new TermQuery(new Term(F_TEXT, token)), BooleanClause.Occur.SHOULD);
            any = true;
        }
        Set<String> rawTokens = new LinkedHashSet<>();
        for (String token : normalisedQuery.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+")) {
            if (token.length() >= MIN_PREFIX) {
                rawTokens.add(token);
            }
        }
        for (String token : rawTokens) {
            // Scoring rewrite so prefix hits get BM25 like any other term,
            // rather than Lucene's default constant score.
            builder.add(new PrefixQuery(new Term(F_TEXT, token), MultiTermQuery.SCORING_BOOLEAN_REWRITE),
                    BooleanClause.Occur.SHOULD);
            any = true;
        }
        return any ? builder.build() : null;
    }

    private List<String> analyse(String text) {
        List<String> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream(F_TEXT, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(term.toString());
            }
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return tokens;
    }

    /** Pins the current generation for the duration of one search. */
    private Generation acquire() {
        for (;;) {
            Generation gen = current.get();
            if (gen == null) {
                return null;
            }
            if (gen.reader.tryIncRef()) {
                return gen;
            }
            // Lost the race with a rebuild that already released this
            // generation's last reference; try the new one.
        }
    }

    @PreDestroy
    void close() {
        Generation gen = current.getAndSet(null);
        if (gen != null) {
            gen.release();
        }
    }

    /**
     * One built index. The reader's reference count is the lifetime: the
     * generation holds one reference from build to swap-out, each search
     * holds one while it runs, and the directory is closed with the last.
     */
    private static final class Generation {
        final Directory directory;
        final DirectoryReader reader;
        final IndexSearcher searcher;
        final IndexStats stats;

        Generation(Directory directory, DirectoryReader reader, IndexSearcher searcher, IndexStats stats) {
            this.directory = directory;
            this.reader = reader;
            this.searcher = searcher;
            this.stats = stats;
        }

        void release() {
            try {
                reader.decRef();
                if (reader.getRefCount() == 0) {
                    directory.close();
                }
            } catch (IOException e) {
                log.warn("Failed to release feature index generation: {}", e.toString());
            }
        }
    }
}
