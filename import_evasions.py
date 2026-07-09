#!/usr/bin/env python3
"""
Builds data/evasions.json for the Evasion reference: id, name, what (from the Arcanum
taxonomy description), why (authored mechanistic explanation of why it evades filters
while the model still decodes it), and examples. Run after refreshing arcanum_source.json.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "taxonomy", "arcanum_source.json")

WHY = {
"a1z26": "Letters are replaced by their alphabet numbers, so the harmful words never appear as text for a classifier to match. The model recognizes the common a=1..z=26 scheme, decodes it, and follows the recovered instruction.",
"ancient_scripts": "Rendering the text in an ancient or non-Latin script changes the bytes the filter sees, and safety training rarely covers these scripts. The model, trained on broad multilingual and script data, still reads the meaning and responds.",
"ascii": "The payload is written as ASCII code points instead of characters, so plaintext pattern matching finds nothing. The model reliably maps codes back to letters and acts on the decoded text.",
"acrostics": "The instruction is hidden in the first letters of lines or words, so the surface text is innocuous to a classifier. The model can be directed to read the acrostic and reconstruct the hidden instruction.",
"alt_language": "Safety alignment is densest in English; posing the request in another language reaches a weaker-guarded region while the model still understands it, so it answers what English-tuned filters do not flag.",
"baconian": "A Baconian cipher hides letters as binary-like A/B patterns, so the harmful string is absent from the text. The model knows the cipher from training, decodes it, and complies.",
"base64": "Classifiers scan for known plaintext strings; base64 turns the payload into an opaque blob they cannot match. The model has decoded base64 countless times in training, so it converts it back and follows it.",
"binary": "The instruction is expressed as binary digits, invisible to plaintext pattern matching. The model decodes the bits to characters and executes the recovered instruction.",
"bijection_learning": "A custom character-to-character mapping is defined inline and the payload encoded with it, so no known harmful string is present. The model learns the bijection from the definition and applies it to decode and follow the instruction the classifier never sees in clear.",
"brainfuck": "Encoding the instruction in an esoteric programming language hides it from text filters. The model can interpret it (or is told how), recovering and acting on the payload.",
"braille": "Braille Unicode patterns replace letters, changing the bytes the filter matches while remaining decodable. The model maps braille back to text and follows it.",
"bubble_text": "Enclosed or circled Unicode look-alikes replace normal letters, so byte-level matching fails, but the glyphs are visually and semantically the same to the model, which reads them normally.",
"case_changing": "aLtErNaTiNg or odd casing breaks exact and many fuzzy string matches while leaving the words fully legible to the model, whose understanding is case-insensitive.",
"cipher": "A simple substitution or shift cipher (e.g. ROT13, Caesar) is defined and the payload encoded, so the plaintext threat string is gone. The model applies the well-known cipher to decode and comply.",
"code_switching": "Randomly switching languages or scripts mid-text defeats classifiers that expect a single consistent language, while the model, trained multilingually, still reconstructs the overall meaning.",
"emoji": "Emoji or emoji-encoded sequences stand in for words, so text matching misses the payload; the model interprets the emoji semantics and recovers the instruction.",
"fictional_constructed_languages": "Constructed or fictional languages (or an inline-defined one) fall outside safety training coverage, so filters miss them, but the model can translate from the provided key or its training and answer.",
"graph_nodes": "The instruction is expressed as nodes and edges of a graph rather than prose, so it is not a matchable string; the model reconstructs the sequence from the graph structure and follows it.",
"fullwidth": "Fullwidth Unicode variants of ASCII are different code points, so byte matching fails, but they are visually identical letters the model reads without difficulty.",
"homoglyphs": "Look-alike characters from other alphabets (e.g. Cyrillic a) replace Latin ones, so the string differs at the byte level from any blocklist entry while looking the same; the model reads it as the intended word.",
"hex": "The payload is hex-encoded, so no plaintext threat string exists for the filter. The model decodes hex to text routinely and follows the instruction.",
"html_entities": "Characters are written as HTML entities (&#x..;), so the raw string is absent from what a natural-language filter scans; the model decodes the entities and acts on the result.",
"invisible_text": "Zero-width or invisible characters are inserted between letters, so exact-match filters see broken or unknown tokens while the visible word is unchanged to the model, which ignores the zero-width characters.",
"japanese_scripts": "Rendering via kana or kanji shifts to a script where English-tuned filters are weak; the model reads Japanese fluently and answers.",
"json": "Wrapping the instruction in a JSON or structured object exploits filters that scan prose, not data structures; the model parses the JSON and treats the field as an instruction.",
"link_smuggling": "The instruction or an exfiltration channel is hidden in a URL or markdown link; filters treat links as benign formatting, and the model may follow the embedded instruction or place data in the URL query.",
"markdown": "Markdown formatting (blockquotes, fences, emphasis) reframes the payload as a document element; natural-language filters miss it, and the model still reads the underlying instruction.",
"metacharacter_confusion": "Swapping in look-alike metacharacters or punctuation breaks exact string and regex matches while the text remains legible to the model.",
"math_symbols": "Mathematical Unicode letter variants replace normal letters, changing bytes for the filter but reading as the same letters to the model.",
"morse": "The payload is in Morse code, absent as plaintext; the model decodes the dots and dashes and follows the recovered instruction.",
"nato_phonetic": "Words are spelled with the NATO alphabet (Alpha, Bravo, ...), so the harmful string is not present; the model takes the first letters and reconstructs it.",
"phoenetic_substitution": "Words are respelled phonetically, breaking exact matching while staying pronounceable and legible; the model maps the sounds back to the intended words.",
"rail_fence": "A rail-fence transposition scrambles letter order by a known pattern, so the string is not matchable; the model reverses the transposition and follows it.",
"regional_indicators": "Regional-indicator (flag) letter symbols replace ASCII letters: different bytes to the filter, the same letters to the model.",
"truncation_misspelling": "Deliberate misspellings or truncations (passw0rd, sytem prmpt) defeat exact and embedding matches while the model still understands the intended word.",
"reverse": "The text is reversed, so forward-scanning matchers miss it, and the model is told to reverse it back and comply.",
"spaces": "Inserting spaces between every character breaks tokenization and string matching while the model still reads the spaced-out word.",
"splats": "Key letters are replaced with splats or asterisks (p*ssw*rd), evading exact matches; the model infers the masked word from context.",
"semaphore": "The instruction is encoded as flag-semaphore positions rather than text, so no string is present; the model (or a provided key) decodes it.",
"small_caps": "Small-caps, subscript, or superscript Unicode variants replace letters, changing bytes for the filter but remaining the same letters to the model.",
"synonyms": "Swapping trigger words for synonyms defeats keyword and close-embedding matches while preserving the meaning the model acts on.",
"stego": "The instruction is hidden inside other content (whitespace patterns, first letters, image data) so it is not visible to scanners; the model is guided to extract and follow it.",
"strikethrough_underline": "Combining-character overlays (strikethrough, underline) attach to letters, altering bytes for the filter while the base letters remain readable to the model.",
"tap_code": "The payload is encoded as tap-code number pairs, absent as plaintext; the model decodes the grid coordinates back to letters.",
"upside_down": "Upside-down Unicode look-alikes replace letters, changing bytes and defeating matching while the model can still read the flipped text or is told to flip it.",
"url_encoding": "Percent-encoding hides the characters from natural-language filters; the model URL-decodes and follows the instruction.",
"vertical_text": "Writing the text vertically (one character per line) breaks horizontal string matching while the model can still read down the column.",
"waveforms": "The instruction is carried as an audio waveform or frequency representation rather than text, outside text filters; the model or pipeline decodes it to text.",
"wingdings": "A symbol font maps letters to glyphs; the underlying code points differ from plaintext, but the model can decode the mapping and read it.",
"xml": "Wrapping the payload in XML tags exploits filters that scan prose; the model parses the structure and treats tagged content as instructions.",
"zalgo": "Stacking combining diacritics over letters changes the byte sequence and defeats matching, while the base letters remain readable to the model.",
"adversarial_poetry": "Casting the request as verse or poetry changes the surface form and reads as a creative task; classifiers tuned on direct requests miss it and the model renders the content as a poem.",
"symbolic_math_encoding": "MathPrompt expresses the harmful request as a symbolic math or set-theory problem; safety training rarely covers math notation, so the filter misses the intent while the model solves the problem and surfaces the content.",
"bidi_override": "Unicode bidirectional override characters reorder how text displays versus its logical byte order (Trojan Source), so what a human or filter sees differs from what the model processes, hiding the real instruction.",
"alt_base_encoding": "Base32/58/85 turn the payload into an opaque encoded blob no plaintext matcher will flag; the model decodes the base and follows it.",
"numeric_codepoint_encoding": "Characters are given as octal or decimal code points, absent as text; the model maps the numbers back to characters and complies.",
"layered_encoding": "Chaining multiple encodings (e.g. base64 of hex) defeats single-pass decoders and simple filters; the model peels the layers and recovers the instruction.",
"compression_encoding": "The payload is compressed (gzip/zlib, often then base64); the bytes are opaque to filters, and the model or tool decompresses it back to the instruction.",
"ansi_escape_concealment": "ANSI terminal escape codes hide or overwrite text in a terminal view, so what is displayed differs from the actual bytes the model reads, concealing the real instruction.",
"query_language_encoding": "QueryAttack expresses the request in a query or DSL syntax (SQL-like), which filters tuned on prose miss; the model interprets the query and produces the requested content.",
"code_structure_encoding": "CodeAttack hides the request inside code structures (function names, comments, string literals) that read as programming, not a harmful request; the model reads or executes the code and surfaces the payload.",
"charset_confusion": "Legacy or ambiguous charsets (GB18030, UTF-7) cause the same bytes to decode differently across the filter and the model, so the filter sees benign text while the model decodes the real instruction.",
"delimiter": "Custom delimiters segment or reassemble the payload so no contiguous harmful string exists for matching; the model recombines the delimited parts and follows the instruction.",
}


# Deterministic text transforms PromptForge implements in Java (work offline AND online).
LOCAL = {
    "base64", "hex", "ascii", "binary", "a1z26", "morse", "nato_phonetic", "cipher",
    "bijection_learning", "reverse", "case_changing", "invisible_text", "spaces",
    "url_encoding", "html_entities", "fullwidth", "zalgo", "bidi_override", "markdown",
    "xml", "json", "emoji", "alt_base_encoding", "regional_indicators",
    "numeric_codepoint_encoding", "splats", "delimiter", "vertical_text",
}
# Non-text media carriers - cannot be produced as a text payload at all.
CARRIER = {"waveforms", "semaphore", "stego", "wingdings"}
# Everything else needs the model to render it (languages, poetry, exotic ciphers, etc.):
# applied by the LLM online, unavailable offline.


def apply_mode(eid):
    if eid in LOCAL:
        return "local"
    if eid in CARRIER:
        return "carrier"
    return "model"


def main():
    src = json.load(open(SRC, encoding="utf-8"))
    out = []
    missing = []
    for e in src["evasions"]:
        eid = e["id"]
        what = (e.get("description") or "").strip()
        examples = [x.strip() for x in (e.get("examples") or []) if x and x.strip()][:4]
        why = WHY.get(eid)
        if why is None:
            missing.append(eid)
            why = "Obfuscates the payload so pattern/embedding-based filters do not match it, while the model still decodes the surface form and follows the instruction."
        out.append({"id": eid, "name": e.get("title", eid), "apply": apply_mode(eid),
                    "what": what, "why": why, "examples": examples})

    with open(os.path.join(HERE, "data", "evasions.json"), "w", encoding="utf-8") as f:
        json.dump({"_comment": "Evasion reference: id, name, what (from Arcanum), why (authored mechanistic), examples.",
                   "evasions": out}, f, indent=2, ensure_ascii=False)
    print("evasions:", len(out), "| with authored why:", sum(1 for x in out if x["id"] in WHY))
    if missing:
        print("used fallback why for:", missing)


if __name__ == "__main__":
    main()
