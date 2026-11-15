#include "assistant/wake_word_tokens.hpp"
#include <cassert>
#include <fstream>
#include <iostream>
#include <string>

int main() {
    using openstation::SentencePieceTokenizer;

    // 1. Embedded representative SentencePiece vocabulary matching ru-vosk-small layout.
    const std::string mini_tokens =
        "<blk> 0\n"
        "<sos/eos> 1\n"
        "<unk> 2\n"
        "\xE2\x96\x81 7\n"
        "\xE2\x96\x81\xD0\xB0 20\n"
        "\xD0\xBB\xD0\xB8 21\n"
        "\xD1\x81\xD0\xB0 22\n"
        "\xD0\xB4 23\n"
        "\xD0\xB6\xD0\xB0 24\n"
        "\xD1\x80 25\n"
        "\xD0\xB2\xD0\xB8 26\n"
        "\xD1\x81 27\n"
        "\xE2\x96\x81\xD0\xBE 28\n"
        "\xD0\xBA\xD0\xB5\xD0\xB9 29\n"
        "\xD0\xBC\xD0\xB0 30\n"
        "\xD0\xBA 31\n"
        "\xD0\xB5 32\n"
        "\xD0\xB6\xD0\xB8 33\n"
        "\xE2\x96\x81\xD0\xB2 34\n"
        "\xD0\xB3\xD0\xB0 35\n"
        "- 36\n";

    SentencePieceTokenizer tok;
    assert(tok.parse_tokens_text(mini_tokens));

    // Plain Cyrillic phrase -> tokenized with @label.
    assert(tok.encode_phrase_line("Алиса") == "\xE2\x96\x81\xD0\xB0 \xD0\xBB\xD0\xB8 \xD1\x81\xD0\xB0 @\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0");
    assert(tok.encode_phrase_line("Джарвис") == "\xE2\x96\x81 \xD0\xB4 \xD0\xB6\xD0\xB0 \xD1\x80 \xD0\xB2\xD0\xB8 \xD1\x81 @\xD0\xB4\xD0\xB6\xD0\xB0\xD1\x80\xD0\xB2\xD0\xB8\xD1\x81");
    assert(tok.encode_phrase_line("Окей Алиса") == "\xE2\x96\x81\xD0\xBE \xD0\xBA\xD0\xB5\xD0\xB9 \xE2\x96\x81\xD0\xB0 \xD0\xBB\xD0\xB8 \xD1\x81\xD0\xB0 @\xD0\xBE\xD0\xBA\xD0\xB5\xD0\xB9_\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0");

    // Latin wake words phonetic transliteration.
    assert(tok.encode_phrase_line("Jarvis") == "\xE2\x96\x81 \xD0\xB4 \xD0\xB6\xD0\xB0 \xD1\x80 \xD0\xB2\xD0\xB8 \xD1\x81 @\xD0\xB4\xD0\xB6\xD0\xB0\xD1\x80\xD0\xB2\xD0\xB8\xD1\x81");
    assert(tok.encode_phrase_line("Alice") == "\xE2\x96\x81\xD0\xB0 \xD0\xBB\xD0\xB8 \xD1\x81\xD0\xB0 @\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0");

    // `ё` fallback -> `е` when `ё` is absent from vocab.
    assert(tok.encode_phrase_line("Ёжик") == "\xE2\x96\x81 \xD0\xB5 \xD0\xB6\xD0\xB8 \xD0\xBA @\xD0\xB5\xD0\xB6\xD0\xB8\xD0\xBA");

    // Already-tokenized Sherpa strings stay verbatim.
    const std::string pre = "\xE2\x96\x81\xD0\xB0 \xD0\xBB\xD0\xB8 \xD1\x81\xD0\xB0 @\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0";
    assert(tok.encode_phrase_line(pre) == pre);

    // Multi-keyword separators (`/`, `,`, `\n`).
    const std::string multi = tok.format_keywords_buf("Алиса, Вега / Jarvis");
    assert(multi.find("@\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0\n") != std::string::npos);
    assert(multi.find("@\xD0\xB2\xD0\xB5\xD0\xB3\xD0\xB0\n") != std::string::npos);
    assert(multi.find("@\xD0\xB4\xD0\xB6\xD0\xB0\xD1\x80\xD0\xB2\xD0\xB8\xD1\x81\n") != std::string::npos);

    // Optional live ru-vosk-small tokens.txt test if path is passed via argv[1].
    if (std::ifstream f("ru_vosk_tokens.txt"); f.good()) {
        SentencePieceTokenizer real_tok;
        assert(real_tok.load_tokens_file("ru_vosk_tokens.txt"));
        for (const std::string& w : {
                 "Алиса", "Джарвис", "Пятница", "Макс",
                 "Вега", "Алёна", "Артём", "Станция",
                 "Окей Алиса", "Окей Макс", "Jarvis", "Friday", "Max", "Alice"
             }) {
            const std::string enc = real_tok.encode_phrase_line(w);
            assert(!enc.empty());
            assert(enc.find(" @") != std::string::npos);
            std::cout << w << " => " << enc << "\n";
        }
    }
    return 0;
}
