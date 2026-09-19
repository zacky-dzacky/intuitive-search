package com.bank.intuitivesearch.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything tunable about the pipeline lives here, so behaviour can be
 * adjusted per environment without touching code.
 */
@ConfigurationProperties(prefix = "search")
public class SearchProperties {

    private int candidateLimit = 10;
    private double navigateThreshold = 0.35;
    private double extractionThreshold = 0.62;
    private double extractionMargin = 0.15;
    private double exactMatchConfidence = 0.97;

    private Weights weights = new Weights();
    private Signal signal = new Signal();
    private Llm llm = new Llm();
    private Embedding embedding = new Embedding();
    private Resolution resolution = new Resolution();

    public static class Weights {
        private double keyword = 0.20;
        private double trigram = 0.15;
        private double vector = 0.35;
        private double containment = 0.30;

        public double getKeyword() { return keyword; }
        public void setKeyword(double keyword) { this.keyword = keyword; }
        public double getTrigram() { return trigram; }
        public void setTrigram(double trigram) { this.trigram = trigram; }
        public double getVector() { return vector; }
        public void setVector(double vector) { this.vector = vector; }
        public double getContainment() { return containment; }
        public void setContainment(double containment) { this.containment = containment; }
    }

    public static class Signal {
        private int threshold = 2;
        private int weightNumber = 2;
        private int weightCurrency = 1;
        private int weightDate = 2;
        private int weightExtraToken = 1;
        private int weightExtraTokenFreeText = 2;
        private int maxExtraTokenScore = 2;
        private String currencyCodes = "";
        private String currencySymbols = "";
        private String dateWords = "";
        private String stopWords = "";

        public int getThreshold() { return threshold; }
        public void setThreshold(int threshold) { this.threshold = threshold; }
        public int getWeightNumber() { return weightNumber; }
        public void setWeightNumber(int weightNumber) { this.weightNumber = weightNumber; }
        public int getWeightCurrency() { return weightCurrency; }
        public void setWeightCurrency(int weightCurrency) { this.weightCurrency = weightCurrency; }
        public int getWeightDate() { return weightDate; }
        public void setWeightDate(int weightDate) { this.weightDate = weightDate; }
        public int getWeightExtraToken() { return weightExtraToken; }
        public void setWeightExtraToken(int weightExtraToken) { this.weightExtraToken = weightExtraToken; }
        public int getWeightExtraTokenFreeText() { return weightExtraTokenFreeText; }
        public void setWeightExtraTokenFreeText(int n) { this.weightExtraTokenFreeText = n; }
        public int getMaxExtraTokenScore() { return maxExtraTokenScore; }
        public void setMaxExtraTokenScore(int maxExtraTokenScore) { this.maxExtraTokenScore = maxExtraTokenScore; }
        public String getCurrencyCodes() { return currencyCodes; }
        public void setCurrencyCodes(String currencyCodes) { this.currencyCodes = currencyCodes; }
        public String getCurrencySymbols() { return currencySymbols; }
        public void setCurrencySymbols(String currencySymbols) { this.currencySymbols = currencySymbols; }
        public String getDateWords() { return dateWords; }
        public void setDateWords(String dateWords) { this.dateWords = dateWords; }
        public String getStopWords() { return stopWords; }
        public void setStopWords(String stopWords) { this.stopWords = stopWords; }

        public Set<String> currencyCodeSet() { return toSet(currencyCodes); }
        public Set<String> currencySymbolSet() { return toSet(currencySymbols); }
        public Set<String> dateWordSet() { return toSet(dateWords); }
        public Set<String> stopWordSet() { return toSet(stopWords); }
    }

    public static class Llm {
        private String provider = "heuristic";
        private long timeoutMs = 2500;
        private int maxOutputTokens = 512;
        private Ollama ollama = new Ollama();
        private Anthropic anthropic = new Anthropic();

        public static class Ollama {
            private String baseUrl = "http://localhost:11434";
            private String model = "qwen2.5:3b-instruct";
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }
        }

        public static class Anthropic {
            private String model = "claude-haiku-4-5";
            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }
        }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
        public Ollama getOllama() { return ollama; }
        public void setOllama(Ollama ollama) { this.ollama = ollama; }
        public Anthropic getAnthropic() { return anthropic; }
        public void setAnthropic(Anthropic anthropic) { this.anthropic = anthropic; }
    }

    public static class Embedding {
        private boolean enabled = true;
        private String baseUrl = "http://localhost:8000";
        /** End-to-end deadline, including time queued for an HTTP connection. */
        private long timeoutMs = 400;
        private int dimensions = 384;
        /** Bulkhead: in-flight embedding calls. Beyond this, skip the channel. */
        private int maxConcurrent = 16;
        private int circuitBreakerFailures = 5;
        private long circuitOpenMs = 5000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
        public int getCircuitBreakerFailures() { return circuitBreakerFailures; }
        public void setCircuitBreakerFailures(int n) { this.circuitBreakerFailures = n; }
        public long getCircuitOpenMs() { return circuitOpenMs; }
        public void setCircuitOpenMs(long circuitOpenMs) { this.circuitOpenMs = circuitOpenMs; }
    }

    public static class Resolution {
        private double minSimilarity = 0.30;
        private double ambiguityMargin = 0.12;
        private int maxCandidates = 5;

        public double getMinSimilarity() { return minSimilarity; }
        public void setMinSimilarity(double minSimilarity) { this.minSimilarity = minSimilarity; }
        public double getAmbiguityMargin() { return ambiguityMargin; }
        public void setAmbiguityMargin(double ambiguityMargin) { this.ambiguityMargin = ambiguityMargin; }
        public int getMaxCandidates() { return maxCandidates; }
        public void setMaxCandidates(int maxCandidates) { this.maxCandidates = maxCandidates; }
    }

    private static Set<String> toSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split("[,\\s]+"))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    public int getCandidateLimit() { return candidateLimit; }
    public void setCandidateLimit(int candidateLimit) { this.candidateLimit = candidateLimit; }
    public double getNavigateThreshold() { return navigateThreshold; }
    public void setNavigateThreshold(double navigateThreshold) { this.navigateThreshold = navigateThreshold; }
    public double getExtractionThreshold() { return extractionThreshold; }
    public void setExtractionThreshold(double extractionThreshold) { this.extractionThreshold = extractionThreshold; }
    public double getExtractionMargin() { return extractionMargin; }
    public void setExtractionMargin(double extractionMargin) { this.extractionMargin = extractionMargin; }
    public double getExactMatchConfidence() { return exactMatchConfidence; }
    public void setExactMatchConfidence(double exactMatchConfidence) { this.exactMatchConfidence = exactMatchConfidence; }
    public Weights getWeights() { return weights; }
    public void setWeights(Weights weights) { this.weights = weights; }
    public Signal getSignal() { return signal; }
    public void setSignal(Signal signal) { this.signal = signal; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }
    public Resolution getResolution() { return resolution; }
    public void setResolution(Resolution resolution) { this.resolution = resolution; }
}
