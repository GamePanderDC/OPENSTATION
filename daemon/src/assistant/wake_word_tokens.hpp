#pragma once

#include <algorithm>
#include <cctype>
#include <fstream>
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>

namespace openstation {

/**
 * SentencePiece greedy longest-match tokenizer for Sherpa-ONNX keyword spotting
 * (`ru-vosk-small/tokens.txt`). Converts plain Russian/Latin wake phrases into
 * space-separated subword tokens plus a single-token `@label` suffix so users
 * can change the assistant's wake word dynamically by name.
 */
class SentencePieceTokenizer {
public:
    bool load_tokens_file(const std::string& path) {
        std::ifstream ifs(path);
        if (!ifs.is_open()) return false;
        std::ostringstream ss;
        ss << ifs.rdbuf();
        return parse_tokens_text(ss.str());
    }

    bool parse_tokens_text(const std::string& text) {
        vocab_.clear();
        max_tok_bytes_ = 0;

        std::istringstream iss(text);
        std::string line;
        while (std::getline(iss, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.empty()) continue;

            const size_t sep = line.find_last_of(" \t");
            const std::string tok = (sep == std::string::npos) ? line : line.substr(0, sep);
            if (tok.empty()) continue;
            // Skip Sherpa control tokens such as <blk>, <sos/eos>, <unk>, #0, #1.
            if (tok.front() == '<' || tok.front() == '#') continue;

            vocab_.insert(tok);
            max_tok_bytes_ = std::max(max_tok_bytes_, tok.size());
        }
        return !vocab_.empty();
    }

    bool empty() const { return vocab_.empty(); }
    bool has_token(const std::string& tok) const { return vocab_.count(tok) > 0; }

    std::vector<std::string> tokenize_phrase(const std::string& phrase) const {
        std::vector<std::string> out;
        if (vocab_.empty()) return out;

        for (const std::string& word : extract_normalized_words(phrase)) {
            const auto word_tokens = tokenize_normalized_word(word);
            out.insert(out.end(), word_tokens.begin(), word_tokens.end());
        }
        return out;
    }

    std::string encode_phrase_line(const std::string& raw_entry) const {
        const std::string trimmed = trim_copy(raw_entry);
        if (trimmed.empty()) return "";

        // Keep already-tokenized Sherpa KWS entries (containing SentencePiece `▁`) untouched.
        if (trimmed.find("\xE2\x96\x81") != std::string::npos) {
            return trimmed;
        }

        // Preserve optional trailing `:boost` / `#thresh` / `@display` modifiers if present.
        std::string phrase_part = trimmed;
        std::string modifiers;
        const size_t mod_pos = trimmed.find_first_of(":#@");
        if (mod_pos != std::string::npos) {
            phrase_part = trim_copy(trimmed.substr(0, mod_pos));
            modifiers = trim_copy(trimmed.substr(mod_pos));
        }

        const auto words = extract_normalized_words(phrase_part);
        if (words.empty()) return "";

        std::string out;
        std::string label;
        for (size_t i = 0; i < words.size(); ++i) {
            const auto toks = tokenize_normalized_word(words[i]);
            if (toks.empty()) continue;
            for (const std::string& t : toks) {
                if (!out.empty()) out.push_back(' ');
                out += t;
            }
            if (!label.empty()) label.push_back('_');
            label += words[i];
        }
        if (out.empty()) return "";

        if (!modifiers.empty()) {
            out.push_back(' ');
            out += modifiers;
            if (modifiers.find('@') == std::string::npos && !label.empty()) {
                out += " @" + label;
            }
        } else if (!label.empty()) {
            out += " @" + label;
        }
        return out;
    }

    std::string format_keywords_buf(const std::string& raw_input) const {
        static constexpr const char* kDefaultKeyword =
            "\xE2\x96\x81\xD0\xB0 \xD0\xBB\xD0\xB8 \xD1\x81\xD0\xB0 @\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0";

        std::string cleaned = raw_input;
        cleaned.erase(std::remove(cleaned.begin(), cleaned.end(), '\r'), cleaned.end());

        std::vector<std::string> lines;
        std::string cur;
        for (char ch : cleaned) {
            if (ch == '\n' || ch == '/' || ch == ';' || ch == ',') {
                const std::string encoded = vocab_.empty() ? trim_copy(cur) : encode_phrase_line(cur);
                if (!encoded.empty()) lines.push_back(encoded);
                cur.clear();
            } else {
                cur.push_back(ch);
            }
        }
        if (!cur.empty()) {
            const std::string encoded = vocab_.empty() ? trim_copy(cur) : encode_phrase_line(cur);
            if (!encoded.empty()) lines.push_back(encoded);
        }

        std::string result;
        for (const std::string& l : lines) {
            result += l;
            result.push_back('\n');
        }
        if (result.empty()) {
            result = std::string(kDefaultKeyword) + '\n';
        }
        return result;
    }

private:
    std::unordered_set<std::string> vocab_;
    size_t max_tok_bytes_ = 0;

    static std::string trim_copy(const std::string& s) {
        size_t start = 0;
        while (start < s.size() && std::isspace(static_cast<unsigned char>(s[start]))) ++start;
        size_t end = s.size();
        while (end > start && std::isspace(static_cast<unsigned char>(s[end - 1]))) --end;
        return s.substr(start, end - start);
    }

    static bool next_utf8(const std::string& s, size_t& i, uint32_t& cp, size_t& bytes) {
        if (i >= s.size()) return false;
        const uint8_t b0 = static_cast<uint8_t>(s[i]);
        if (b0 < 0x80) {
            cp = b0;
            bytes = 1;
        } else if ((b0 & 0xE0) == 0xC0 && i + 1 < s.size()) {
            cp = ((b0 & 0x1FU) << 6) | (static_cast<uint8_t>(s[i + 1]) & 0x3FU);
            bytes = 2;
        } else if ((b0 & 0xF0) == 0xE0 && i + 2 < s.size()) {
            cp = ((b0 & 0x0FU) << 12) |
                 ((static_cast<uint8_t>(s[i + 1]) & 0x3FU) << 6) |
                 (static_cast<uint8_t>(s[i + 2]) & 0x3FU);
            bytes = 3;
        } else if ((b0 & 0xF8) == 0xF0 && i + 3 < s.size()) {
            cp = ((b0 & 0x07U) << 18) |
                 ((static_cast<uint8_t>(s[i + 1]) & 0x3FU) << 12) |
                 ((static_cast<uint8_t>(s[i + 2]) & 0x3FU) << 6) |
                 (static_cast<uint8_t>(s[i + 3]) & 0x3FU);
            bytes = 4;
        } else {
            cp = b0;
            bytes = 1;
        }
        i += bytes;
        return true;
    }

    static void append_utf8(std::string& out, uint32_t cp) {
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    static std::string transliterate_latin_word(const std::string& w) {
        if (w == "alice") return "\xD0\xB0\xD0\xBB\xD0\xB8\xD1\x81\xD0\xB0";           // алиса
        if (w == "jarvis") return "\xD0\xB4\xD0\xB6\xD0\xB0\xD1\x80\xD0\xB2\xD0\xB8\xD1\x81"; // джарвис
        if (w == "friday") return "\xD1\x84\xD1\x80\xD0\xB0\xD0\xB9\xD0\xB4\xD0\xB5\xD0\xB9"; // фрайдэй
        if (w == "vega") return "\xD0\xB2\xD0\xB5\xD0\xB3\xD0\xB0";             // вега

        static constexpr struct {
            const char* lat;
            const char* cyr;
        } kDigraphs[] = {
            {"shch", "щ"}, {"sch", "щ"}, {"zh", "ж"}, {"ch", "ч"}, {"sh", "ш"},
            {"yu", "ю"}, {"ya", "я"}, {"yo", "е"}, {"ye", "е"}, {"kh", "х"},
            {"ts", "ц"}, {"th", "т"}, {"ph", "ф"}, {"qu", "кв"}, {"dj", "дж"},
            {"oo", "у"}, {"ee", "и"}, {"ea", "и"}, {"ai", "ей"}, {"ay", "ей"},
            {"ey", "ей"}, {"ck", "к"}
        };

        std::string out;
        for (size_t i = 0; i < w.size();) {
            bool matched = false;
            for (const auto& d : kDigraphs) {
                const size_t n = std::char_traits<char>::length(d.lat);
                if (i + n <= w.size() && w.compare(i, n, d.lat) == 0) {
                    out += d.cyr;
                    i += n;
                    matched = true;
                    break;
                }
            }
            if (matched) continue;

            const char c = w[i++];
            switch (c) {
                case 'a': out += "а"; break;
                case 'b': out += "б"; break;
                case 'c': {
                    const char nxt = (i < w.size()) ? w[i] : '\0';
                    out += (nxt == 'e' || nxt == 'i' || nxt == 'y') ? "с" : "к";
                    break;
                }
                case 'd': out += "д"; break;
                case 'e': out += "е"; break;
                case 'f': out += "ф"; break;
                case 'g': out += "г"; break;
                case 'h': out += "х"; break;
                case 'i': out += "и"; break;
                case 'j': out += "дж"; break;
                case 'k': out += "к"; break;
                case 'l': out += "л"; break;
                case 'm': out += "м"; break;
                case 'n': out += "н"; break;
                case 'o': out += "о"; break;
                case 'p': out += "п"; break;
                case 'q': out += "к"; break;
                case 'r': out += "р"; break;
                case 's': out += "с"; break;
                case 't': out += "т"; break;
                case 'u': out += "у"; break;
                case 'v': out += "в"; break;
                case 'w': out += "в"; break;
                case 'x': out += "кс"; break;
                case 'y': out += "и"; break;
                case 'z': out += "з"; break;
                default: break;
            }
        }
        return out;
    }

    std::vector<std::string> extract_normalized_words(const std::string& phrase) const {
        const bool keep_yo = has_token("\xD1\x91"); // "ё"
        std::vector<std::string> words;
        std::string cur;
        std::string lat_buf;

        auto flush_lat = [&]() {
            if (!lat_buf.empty()) {
                cur += transliterate_latin_word(lat_buf);
                lat_buf.clear();
            }
        };
        auto flush_word = [&]() {
            flush_lat();
            if (!cur.empty()) {
                words.push_back(std::move(cur));
                cur.clear();
            }
        };

        for (size_t i = 0; i < phrase.size();) {
            uint32_t cp = 0;
            size_t b = 0;
            next_utf8(phrase, i, cp, b);

            if (cp >= 'A' && cp <= 'Z') {
                lat_buf.push_back(static_cast<char>(cp - 'A' + 'a'));
            } else if (cp >= 'a' && cp <= 'z') {
                lat_buf.push_back(static_cast<char>(cp));
            } else if (cp >= 0x0410 && cp <= 0x042F) {
                flush_lat();
                append_utf8(cur, cp + 0x20);
            } else if (cp >= 0x0430 && cp <= 0x044F) {
                flush_lat();
                append_utf8(cur, cp);
            } else if (cp == 0x0401 || cp == 0x0451) { // Ё / ё
                flush_lat();
                append_utf8(cur, keep_yo ? 0x0451U : 0x0435U);
            } else if (cp == '-' && !cur.empty()) {
                flush_lat();
                if (has_token("-")) cur.push_back('-');
            } else if (cp == '\'' || cp == 0x2019) {
                // Skip apostrophes inside names while preserving word continuity.
            } else {
                flush_word();
            }
        }
        flush_word();
        return words;
    }

    std::vector<std::string> tokenize_normalized_word(const std::string& word) const {
        std::vector<std::string> out;
        if (word.empty() || vocab_.empty()) return out;

        const std::string s = std::string("\xE2\x96\x81") + word;
        size_t pos = 0;
        while (pos < s.size()) {
            const size_t max_len = std::min(s.size() - pos, max_tok_bytes_);
            std::string matched;
            for (size_t len = max_len; len >= 1; --len) {
                const std::string cand = s.substr(pos, len);
                // Only match bare SentencePiece space marker `▁` right at word start when needed.
                if (pos > 0 && cand == "\xE2\x96\x81") continue;
                if (vocab_.count(cand) > 0) {
                    matched = cand;
                    break;
                }
            }
            if (!matched.empty()) {
                pos += matched.size();
                out.push_back(std::move(matched));
            } else {
                size_t tmp = pos;
                uint32_t cp = 0;
                size_t b = 1;
                next_utf8(s, tmp, cp, b);
                pos += b;
            }
        }
        return out;
    }
};

} // namespace openstation
