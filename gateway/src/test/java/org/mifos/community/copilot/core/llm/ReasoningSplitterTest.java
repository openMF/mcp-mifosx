/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Keeping a model's private deliberation out of what it tells the officer.
 *
 * <p>The whole difficulty is that deltas are fragments. A marker split as {@code "<th"} then
 * {@code "ink>"} is ordinary, and anything released early is on the officer's screen for good.
 * Most of these feed the text one character at a time for that reason: if it survives that, it
 * survives any chunking a provider can produce.
 */
class ReasoningSplitterTest {

    private final List<String> answer = new ArrayList<>();
    private final List<String> reasoning = new ArrayList<>();
    private final ReasoningSplitter splitter = new ReasoningSplitter(answer::add, reasoning::add);

    private String answerText() {
        return String.join("", answer);
    }

    private String reasoningText() {
        return String.join("", reasoning);
    }

    /** Feed it the cruellest chunking there is. */
    private void streamOneCharacterAtATime(String whole) {
        for (int i = 0; i < whole.length(); i++) {
            splitter.accept(String.valueOf(whole.charAt(i)));
        }
        splitter.finish();
    }

    @Test
    void textWithNoThinkingIsJustTheAnswer() {
        streamOneCharacterAtATime("Aisha Bello has one active loan.");

        assertThat(answerText()).isEqualTo("Aisha Bello has one active loan.");
        assertThat(reasoningText()).isEmpty();
        assertThat(splitter.sawReasoning()).isFalse();
    }

    @Test
    void thinkingIsSeparatedFromTheAnswer() {
        streamOneCharacterAtATime("<think>The officer wants the balance.</think>The balance is USD 500.");

        assertThat(reasoningText()).isEqualTo("The officer wants the balance.");
        assertThat(answerText()).isEqualTo("The balance is USD 500.");
        assertThat(splitter.sawReasoning()).isTrue();
    }

    /**
     * The failure this exists to prevent. Released eagerly, the officer reads "&lt;th" in the
     * middle of a loan decision and there is no taking it back.
     */
    @Test
    void aMarkerSplitAcrossChunksNeverLeaks() {
        splitter.accept("Before ");
        splitter.accept("<th");
        splitter.accept("ink>");
        splitter.accept("private");
        splitter.accept("</thi");
        splitter.accept("nk>");
        splitter.accept(" after");
        splitter.finish();

        assertThat(answerText()).isEqualTo("Before  after");
        assertThat(reasoningText()).isEqualTo("private");
        assertThat(answerText()).doesNotContain("<").doesNotContain("th ink");
    }

    /** A stray angle bracket is text, and holding it forever would stall the stream. */
    @Test
    void somethingThatOnlyLookedLikeAMarkerIsReleased() {
        streamOneCharacterAtATime("5 < 10 and a > b");

        assertThat(answerText()).isEqualTo("5 < 10 and a > b");
        assertThat(reasoningText()).isEmpty();
    }

    @Test
    void aPartialMarkerThatGoesNowhereIsStillText() {
        streamOneCharacterAtATime("the tag <thin is not a tag");

        assertThat(answerText()).isEqualTo("the tag <thin is not a tag");
        assertThat(reasoningText()).isEmpty();
    }

    /** A cancelled or truncated turn must not tip half a thought into the answer. */
    @Test
    void aStreamThatEndsMidThoughtKeepsItsThoughtWhereItBelongs() {
        splitter.accept("<think>still deciding");
        splitter.finish();

        assertThat(reasoningText()).isEqualTo("still deciding");
        assertThat(answerText()).isEmpty();
    }

    @Test
    void severalThoughtsAreAllCollected() {
        streamOneCharacterAtATime("<think>one</think>A<think>two</think>B");

        assertThat(reasoningText()).isEqualTo("onetwo");
        assertThat(answerText()).isEqualTo("AB");
    }

    @Test
    void nothingAtAllIsHandledWithoutComplaint() {
        splitter.accept(null);
        splitter.accept("");
        splitter.finish();

        assertThat(answerText()).isEmpty();
        assertThat(reasoningText()).isEmpty();
    }

    /** Whole markers arriving in one delta is the easy case, and it still has to work. */
    @Test
    void anEntireExchangeInOneDelta() {
        splitter.accept("<think>quick</think>done");
        splitter.finish();

        assertThat(reasoningText()).isEqualTo("quick");
        assertThat(answerText()).isEqualTo("done");
    }
}
