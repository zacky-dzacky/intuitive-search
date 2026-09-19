package com.bank.intuitivesearch.index;

/** One feature's score from a single retrieval channel, each in 0..1. */
public record ChannelHit(String featureId, double keywordScore,
                         double trigramScore, double vectorScore) {}
