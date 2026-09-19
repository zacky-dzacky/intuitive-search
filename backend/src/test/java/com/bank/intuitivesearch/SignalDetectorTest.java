package com.bank.intuitivesearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.SignalReport;
import com.bank.intuitivesearch.model.SlotDefinition;
import com.bank.intuitivesearch.signal.SignalDetector;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The signal gate is the single most important cost control in the system:
 * it decides whether an LLM is ever called. These tests pin the behaviour the
 * constraint depends on.
 */
class SignalDetectorTest {

    private SignalDetector detector;

    private static final Feature TRANSFER = new Feature(
            "transfer", "Transfer Money", "Send money to a payee", "payments",
            "/payments/transfer",
            List.of("transfer", "send", "money", "payment"),
            List.of("trf", "tf"),
            true,
            List.of(
                    new SlotDefinition("recipient", "string", "who", true, List.of(), "payee"),
                    new SlotDefinition("amount", "number", "how much", true, List.of(), "amount"),
                    new SlotDefinition("currency", "string", "iso code", false, List.of(), "currency")));

    /** Every slot is number/date/currency shaped — nothing a bare word can fill. */
    private static final Feature EXCHANGE = new Feature(
            "currency_exchange", "Currency Exchange", "Convert between currencies",
            "payments", "/payments/exchange",
            List.of("exchange", "convert"), List.of("fx"),
            true,
            List.of(
                    new SlotDefinition("amount", "number", "how much", true, List.of(), "amount"),
                    new SlotDefinition("from_currency", "string", "iso", true, List.of(), "currency"),
                    new SlotDefinition("to_currency", "string", "iso", true, List.of(), "currency")));

    private static final Feature CHECK_BALANCE = new Feature(
            "check_balance", "Check Balance", "See your balance", "accounts",
            "/accounts/balance",
            List.of("balance", "saldo"), List.of("bal"),
            false, List.of());

    @BeforeEach
    void setUp() {
        SearchProperties properties = new SearchProperties();
        SearchProperties.Signal signal = properties.getSignal();
        signal.setCurrencyCodes("usd,eur,sgd,idr,gbp");
        signal.setCurrencySymbols("$,€,£,rp");
        signal.setDateWords("today,tomorrow,march,last,next,month,week,friday");
        signal.setStopWords("a,the,to,for,my,me,i,please,s,of,and,want,need,like");
        detector = new SignalDetector(properties);
    }

    @Test
    @DisplayName("a feature with no parameters can never reach the LLM")
    void navigationOnlyFeatureNeverExtracts() {
        SignalReport report = detector.detect("check balance right now 5000", CHECK_BALANCE);

        assertThat(report.shouldExtract()).isFalse();
        assertThat(report.reason()).isEqualTo("feature declares no parameters");
    }

    @Test
    @DisplayName("a bare feature name produces no signal")
    void bareFeatureNameDoesNotExtract() {
        assertThat(detector.detect("transfer", TRANSFER).shouldExtract()).isFalse();
        assertThat(detector.detect("trf", TRANSFER).shouldExtract()).isFalse();
        assertThat(detector.detect("send money", TRANSFER).shouldExtract()).isFalse();
    }

    @Test
    @DisplayName("the worked example from the spec opens the gate")
    void transactionalQueryExtracts() {
        SignalReport report = detector.detect("transfer to mom's 10000 usd", TRANSFER);

        assertThat(report.shouldExtract()).isTrue();
        assertThat(report.signals())
                .anyMatch(s -> s.startsWith("number:"))
                .anyMatch(s -> s.startsWith("currency:"));
        assertThat(report.extraTokens()).contains("mom");
    }

    @Test
    @DisplayName("a currency symbol counts even when glued to the amount")
    void currencySymbolIsDetected() {
        SignalReport report = detector.detect("transfer $250 to landlord", TRANSFER);

        assertThat(report.shouldExtract()).isTrue();
        assertThat(report.signals()).anyMatch(s -> s.startsWith("currency:"));
    }

    @Test
    @DisplayName("prefix typing partway through a word does not trigger extraction")
    void partialTypingDoesNotExtract() {
        // This is the keystroke case: the user is still typing the feature name.
        assertThat(detector.detect("tran", TRANSFER).shouldExtract()).isFalse();
        assertThat(detector.detect("transf", TRANSFER).shouldExtract()).isFalse();
    }

    @Test
    @DisplayName("date words alone are enough signal alongside another token")
    void dateWordContributesSignal() {
        SignalReport report = detector.detect("transfer to bestie next friday", TRANSFER);

        assertThat(report.shouldExtract()).isTrue();
        assertThat(report.signals()).anyMatch(s -> s.startsWith("date:"));
    }

    @Test
    @DisplayName("feature vocabulary and filler words are not counted as parameters")
    void vocabularyIsNotExtraContent() {
        SignalReport report = detector.detect("i want to send money please", TRANSFER);

        assertThat(report.extraTokens()).isEmpty();
        assertThat(report.shouldExtract()).isFalse();
    }

    @Test
    @DisplayName("a one-word payee is enough when a slot can hold a bare word")
    void loneNameOpensTheGateForAFreeTextSlot() {
        // The name is the entire payload: there is nothing else to type. How
        // many words it happens to have says nothing about intent.
        SignalReport report = detector.detect("transfer to mom", TRANSFER);

        assertThat(report.shouldExtract()).isTrue();
        assertThat(report.score()).isEqualTo(2);
        assertThat(report.extraTokens()).containsExactly("mom");
    }

    @Test
    @DisplayName("a schema with no free-text slot still needs more than one token")
    void loneTokenDoesNotOpenTheGateWithoutAFreeTextSlot() {
        // Every slot here wants a number or a currency, and both score on their
        // own — a stray word is not evidence that one was supplied.
        SignalReport report = detector.detect("exchange something", EXCHANGE);

        assertThat(report.extraTokens()).containsExactly("something");
        assertThat(report.score()).isEqualTo(1);
        assertThat(report.shouldExtract()).isFalse();
    }

    @Test
    @DisplayName("a prefix of the feature's own vocabulary is not parameter residue")
    void partialFeatureWordIsNotExtraContent() {
        assertThat(detector.detect("tran", TRANSFER).extraTokens()).isEmpty();
        assertThat(detector.detect("transf", TRANSFER).extraTokens()).isEmpty();
        assertThat(detector.detect("mone", TRANSFER).extraTokens()).isEmpty();
    }
}
