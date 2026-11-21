package com.vinicius741.webnovelarchiver.feature.reader

/**
 * The reader page's script. It only shows what the app tells it: `WnaReader.render(sentence,
 * scroll)` highlights a sentence (`-1` clears it) and scrolls by a [ReadAlongScroll] code. Whether
 * to follow is decided by the app ([ReaderReadAlong]); the page never keeps its own copy.
 *
 * So that a lost update cannot leave a stale highlight, the page also asks the app for the current
 * state (`ReaderBridge.readAlongState()`) when it loads, when it becomes visible, and once a second
 * while visible. A double-tap on a sentence calls `ReaderBridge.onSentenceDoubleTap(i)`, and the
 * next-chapter card calls `WnaReader.nextChapter()`.
 *
 * Sentences are elements tagged `data-tts-groups` (see `TtsTextPreparation.prepareTtsAnnotatedHtml`);
 * an element whose spoken sentences could not be split into spans lists several, and the page then
 * estimates where in it the sentence sits.
 */
internal object ReaderReadAlongScript {
    const val PAGE_OBJECT = "WnaReader"
    const val BRIDGE = "ReaderBridge"

    fun renderCall(
        sentence: Int,
        scroll: ReadAlongScroll,
    ): String = "window.$PAGE_OBJECT&&$PAGE_OBJECT.render($sentence,${scroll.code});"

    const val NEXT_CHAPTER_CALL = "$PAGE_OBJECT.nextChapter()"

    val script: String =
        """
        <script>
        (function() {
            var SCROLL_NONE = ${ReadAlongScroll.NONE.code}, SCROLL_INSTANT = ${ReadAlongScroll.INSTANT.code}, SCROLL_IF_OFF_SCREEN = ${ReadAlongScroll.IF_OFF_SCREEN.code};
            var reader = window.$PAGE_OBJECT = {};
            var active = -1;

            function bridge() {
                return window.$BRIDGE || null;
            }
            function sentenceNodes(sentence) {
                return document.querySelectorAll('[data-tts-groups~="' + sentence + '"]');
            }
            // Where the sentence sits on screen (viewport coordinates).
            function sentenceBox(sentence, nodes) {
                var first = nodes[0].getBoundingClientRect();
                if (nodes.length > 1) {
                    return { top: first.top, bottom: nodes[nodes.length - 1].getBoundingClientRect().bottom };
                }
                var groups = (nodes[0].getAttribute('data-tts-groups') || '').trim().split(/\s+/);
                var at = groups.indexOf(String(sentence));
                if (groups.length < 2 || at < 0) return { top: first.top, bottom: first.bottom };
                var share = first.height / groups.length;
                return { top: first.top + share * at, bottom: first.top + share * (at + 1) };
            }
            function bringIntoView(sentence, nodes, scroll) {
                var box = sentenceBox(sentence, nodes);
                var height = window.innerHeight;
                if (scroll === SCROLL_IF_OFF_SCREEN && box.top >= 0 && box.bottom <= height) return;
                // Centre the sentence; one taller than most of the screen starts near the top instead.
                var delta = box.bottom - box.top > height * 0.7 ? box.top - height * 0.15 : (box.top + box.bottom - height) / 2;
                if (Math.abs(delta) < 4) return;
                var target = Math.max(0, window.scrollY + delta);
                var instant = scroll === SCROLL_INSTANT || Math.abs(delta) > height * 2;
                window.scrollTo({ top: target, behavior: instant ? 'auto' : 'smooth' });
            }
            reader.render = function(sentence, scroll) {
                try {
                    if (sentence !== active) {
                        document.querySelectorAll('.tts-active').forEach(function(node) { node.classList.remove('tts-active'); });
                        active = sentence;
                        if (active >= 0) sentenceNodes(active).forEach(function(node) { node.classList.add('tts-active'); });
                    }
                    if (active < 0 || scroll === SCROLL_NONE) return;
                    var nodes = sentenceNodes(active);
                    if (nodes.length > 0) bringIntoView(active, nodes, scroll);
                } catch (e) { /* a broken highlight must not break the page */ }
            };
            function sync(scroll) {
                var app = bridge();
                if (!app) return;
                try {
                    var parts = String(app.readAlongState()).split(' ');
                    reader.render(Number(parts[0]), parts[1] === '1' ? scroll : SCROLL_NONE);
                } catch (e) { /* the app may be tearing the page down */ }
            }
            reader.nextChapter = function() {
                var app = bridge();
                if (app) app.onNextChapter();
            };

            sync(SCROLL_INSTANT);
            document.addEventListener('visibilitychange', function() {
                if (!document.hidden) sync(SCROLL_IF_OFF_SCREEN);
            });
            setInterval(function() {
                if (!document.hidden) sync(SCROLL_IF_OFF_SCREEN);
            }, 1000);

            // Double-tap a sentence to read aloud from it.
            function sentenceAt(target) {
                var node = target;
                while (node && node !== document.body) {
                    if (node.dataset) {
                        if (node.dataset.ttsGroup) return node.dataset.ttsGroup;
                        // Several sentences without per-sentence spans: start at the element's first.
                        if (node.dataset.ttsGroups) {
                            var tokens = node.dataset.ttsGroups.trim().split(/\s+/);
                            if (tokens.length) return tokens[0];
                        }
                    }
                    node = node.parentElement;
                }
                return null;
            }
            function startAt(sentence) {
                var app = bridge();
                if (sentence !== null && app) app.onSentenceDoubleTap(Number(sentence));
            }
            var lastTapAt = 0, lastTapSentence = null;
            document.addEventListener('dblclick', function(event) {
                startAt(sentenceAt(event.target));
            });
            document.addEventListener('touchend', function(event) {
                var sentence = sentenceAt(event.target);
                var now = Date.now();
                if (sentence !== null && sentence === lastTapSentence && now - lastTapAt < 350) {
                    startAt(sentence);
                    if (event.cancelable) event.preventDefault();
                }
                lastTapAt = now;
                lastTapSentence = sentence;
            }, { passive: false });
        })();
        </script>
        """.trimIndent()
}
