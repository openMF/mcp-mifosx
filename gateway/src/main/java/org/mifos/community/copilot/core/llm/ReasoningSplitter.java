/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.llm;

import java.util.function.Consumer;

/**
 * Separates a model's reasoning from its answer when the two arrive down the same channel.
 *
 * <p>Providers do this three different ways. Ollama and DeepSeek put the reasoning in its own
 * delta field, which needs no help. The third way is a model that writes its reasoning inline
 * between {@code <think>} and {@code </think>} and leaves whoever is reading to work it out.
 * Left alone, an officer watching a loan decision stream in reads the model's private
 * deliberation as though it were advice.
 *
 * <p>The hard part is that deltas are fragments, not lines. A marker arrives split across
 * chunks as readily as not: {@code "<th"} then {@code "ink>"}. Emitting eagerly would leak
 * {@code "<th"} into the answer, and there is no taking it back once it is on the officer's
 * screen. So any tail that could still turn into a marker is held until the next chunk decides
 * it, and released the moment it cannot.
 *
 * <p>Not a general HTML parser and deliberately so: it recognises two exact markers and treats
 * everything else as text.
 */
final class ReasoningSplitter {

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private final Consumer<String> onAnswer;
    private final Consumer<String> onReasoning;

    /** Text held back because it may yet prove to be the start of a marker. */
    private final StringBuilder pending = new StringBuilder();
    private boolean thinking;

    ReasoningSplitter(Consumer<String> onAnswer, Consumer<String> onReasoning) {
        this.onAnswer = onAnswer;
        this.onReasoning = onReasoning;
    }

    /** Whether anything at all has been recognised as reasoning. */
    boolean sawReasoning() {
        return sawReasoning;
    }

    private boolean sawReasoning;

    void accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        pending.append(delta);
        drain(false);
    }

    /**
     * No more deltas are coming, so nothing can still become a marker.
     *
     * <p>Whatever is held goes out as what it turned out to be. A stream that ended mid-thought
     * keeps its reasoning on the reasoning channel rather than tipping it into the answer.
     */
    void finish() {
        drain(true);
    }

    private void drain(boolean atEnd) {
        while (true) {
            String marker = thinking ? CLOSE : OPEN;
            int at = pending.indexOf(marker);
            if (at >= 0) {
                emit(pending.substring(0, at));
                pending.delete(0, at + marker.length());
                thinking = !thinking;
                if (thinking) {
                    sawReasoning = true;
                }
                continue;
            }
            // No complete marker. Release everything that cannot be the start of one.
            int hold = atEnd ? 0 : danglingPrefix(pending, marker);
            if (pending.length() > hold) {
                emit(pending.substring(0, pending.length() - hold));
                pending.delete(0, pending.length() - hold);
            }
            if (atEnd && pending.length() > 0) {
                emit(pending.toString());
                pending.setLength(0);
            }
            return;
        }
    }

    private void emit(String text) {
        if (text.isEmpty()) {
            return;
        }
        if (thinking) {
            sawReasoning = true;
            onReasoning.accept(text);
        } else {
            onAnswer.accept(text);
        }
    }

    /**
     * How many trailing characters could still grow into {@code marker}.
     *
     * <p>Checked longest first, so {@code "abc<thin"} holds eight rather than nothing: the
     * shorter suffixes of a partial marker are not themselves prefixes of it.
     */
    private static int danglingPrefix(CharSequence text, String marker) {
        int most = Math.min(marker.length() - 1, text.length());
        for (int length = most; length > 0; length--) {
            int from = text.length() - length;
            boolean matches = true;
            for (int i = 0; i < length; i++) {
                if (text.charAt(from + i) != marker.charAt(i)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return length;
            }
        }
        return 0;
    }
}
