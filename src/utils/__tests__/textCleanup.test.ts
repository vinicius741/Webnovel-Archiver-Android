import { RegexCleanupRule } from "../../types";
import {
  applyDownloadCleanup,
  applyTtsCleanupLines,
  removeUnwantedSentences,
  validateRegexCleanupRule,
} from "../textCleanup";

describe("textCleanup", () => {
  describe("validateRegexCleanupRule", () => {
    it("should validate a correct regex rule", () => {
      const result = validateRegexCleanupRule({
        name: "Remove separators",
        pattern: "(?:[-=]){5,}",
        flags: "gi",
      });

      expect(result.valid).toBe(true);
      expect(result.normalizedPattern).toBe("(?:[-=]){5,}");
      expect(result.normalizedFlags).toBe("gi");
    });

    it("should accept JavaScript-style /pattern/flags input", () => {
      const result = validateRegexCleanupRule({
        name: "Literal regex",
        pattern: "/^[\\s]*[—-]{3,}[\\s]*$/gm",
        flags: "",
      });

      expect(result.valid).toBe(true);
      expect(result.normalizedPattern).toBe("^[\\s]*[—-]{3,}[\\s]*$");
      expect(result.normalizedFlags).toBe("gm");
    });

    it("should reject regex literals with unsupported flags", () => {
      const result = validateRegexCleanupRule({
        name: "Bad literal flag",
        pattern: "/abc/z",
        flags: "",
      });

      expect(result.valid).toBe(false);
      expect(result.error).toContain("Unsupported regex flag");
    });

    it("should parse escaped slashes in regex literals", () => {
      const result = validateRegexCleanupRule({
        name: "Escaped slash",
        pattern: "/a\\/b/g",
        flags: "",
      });

      expect(result.valid).toBe(true);
      expect(result.normalizedPattern).toBe("a\\/b");
      expect(result.normalizedFlags).toBe("g");
    });

    it("should reject malformed regex literals", () => {
      const result = validateRegexCleanupRule({
        name: "Malformed literal",
        pattern: "/abc/gm$",
        flags: "",
      });

      expect(result.valid).toBe(false);
      expect(result.error).toContain("Invalid regex literal");
    });

    it("should reject invalid regex patterns", () => {
      const result = validateRegexCleanupRule({
        name: "Bad pattern",
        pattern: "[",
        flags: "i",
      });

      expect(result.valid).toBe(false);
      expect(result.error).toContain("Invalid regex");
    });

    it("should reject unsupported regex flags", () => {
      const result = validateRegexCleanupRule({
        name: "Bad flag",
        pattern: "abc",
        flags: "x",
      });

      expect(result.valid).toBe(false);
      expect(result.error).toContain("Unsupported regex flag");
    });

    it("should reject potentially unsafe regex patterns", () => {
      const result = validateRegexCleanupRule({
        name: "Nested quantifier",
        pattern: "(.+)+",
        flags: "",
      });

      expect(result.valid).toBe(false);
      expect(result.error).toContain("Unsafe regex pattern");
    });

    it("should reject empty names and patterns", () => {
      const noName = validateRegexCleanupRule({
        name: "",
        pattern: "abc",
        flags: "",
      });
      const noPattern = validateRegexCleanupRule({
        name: "Test",
        pattern: "",
        flags: "",
      });

      expect(noName.valid).toBe(false);
      expect(noPattern.valid).toBe(false);
    });
  });

  describe("removeUnwantedSentences", () => {
    it("should preserve existing exact sentence removal behavior", () => {
      const content = "This is a test. Unwanted sentence. More content.";
      const removalList = ["Unwanted sentence."];
      const expected = "This is a test.  More content.";
      expect(removeUnwantedSentences(content, removalList)).toBe(expected);
    });
  });

  describe("applyTtsCleanupLines", () => {
    const separatorRule: RegexCleanupRule = {
      id: "rule-separators",
      name: "Remove separators",
      pattern: "(?:[-=]){5,}",
      flags: "",
      enabled: true,
      appliesTo: "both",
    };

    it("should remove long separator lines", () => {
      const input = "Intro\n-----\nMiddle\n=======\nOutro";
      const output = applyTtsCleanupLines(input, [separatorRule]);
      expect(output).toBe("Intro\n\nMiddle\n\nOutro");
    });

    it("should treat hyphen separator quantifiers as dash-like separators", () => {
      const hyphenRule: RegexCleanupRule = {
        id: "rule-hyphen",
        name: "Remove hyphen separators",
        pattern: "^[\\s]*-{3,}[\\s]*$",
        flags: "gm",
        enabled: true,
        appliesTo: "tts",
      };

      const input = "Intro\n—--------------------------\nOutro";
      const output = applyTtsCleanupLines(input, [hyphenRule]);

      expect(output).toBe("Intro\n\nOutro");
    });

    it("should not expand hyphen quantifiers inside character classes", () => {
      const classRule: RegexCleanupRule = {
        id: "rule-class-hyphen",
        name: "Only hyphen class",
        pattern: "^[\\s]*[-]{3,}[\\s]*$",
        flags: "gm",
        enabled: true,
        appliesTo: "tts",
      };

      const input = "Intro\n—--------------------------\n-----\nOutro";
      const output = applyTtsCleanupLines(input, [classRule]);

      expect(output).toBe("Intro\n—--------------------------\n\nOutro");
    });

    it("should keep escaped hyphen quantifiers literal", () => {
      const escapedRule: RegexCleanupRule = {
        id: "rule-escaped-hyphen",
        name: "Escaped hyphen",
        pattern: "^[\\s]*\\-{3,}[\\s]*$",
        flags: "gm",
        enabled: true,
        appliesTo: "tts",
      };

      const input = "Intro\n—--------------------------\n-----\nOutro";
      const output = applyTtsCleanupLines(input, [escapedRule]);

      expect(output).toBe("Intro\n—--------------------------\n\nOutro");
    });

    it("should expand multiple hyphen quantifiers in one pattern", () => {
      const doubleRule: RegexCleanupRule = {
        id: "rule-double-hyphen",
        name: "Double edge separators",
        pattern: "^-{3,}.*-{3,}$",
        flags: "gm",
        enabled: true,
        appliesTo: "tts",
      };

      const input = "Intro\n—----middle----—\nOutro";
      const output = applyTtsCleanupLines(input, [doubleRule]);

      expect(output).toBe("Intro\n\nOutro");
    });

    it("should apply multiple rules in order", () => {
      const rules: RegexCleanupRule[] = [
        separatorRule,
        {
          id: "rule-stars",
          name: "Remove stars",
          pattern: "\\*{3,}",
          flags: "",
          enabled: true,
          appliesTo: "tts",
        },
      ];

      const input = "A\n*****\n-----\nB";
      const output = applyTtsCleanupLines(input, rules);
      expect(output).toBe("A\n\n\nB");
    });

    it("should keep content if rule is malformed", () => {
      const malformedRule: RegexCleanupRule = {
        ...separatorRule,
        id: "bad-rule",
        name: "Bad rule",
        pattern: "[",
      };

      const input = "Hello\n-----\nWorld";
      const output = applyTtsCleanupLines(input, [malformedRule]);
      expect(output).toBe(input);
    });

    it("should cap total replacements to prevent runaway cleanup", () => {
      const removeEveryCharacterRule: RegexCleanupRule = {
        id: "rule-all",
        name: "Remove all chars",
        pattern: ".",
        flags: "",
        enabled: true,
        appliesTo: "tts",
      };

      const input = "a".repeat(6000);
      const output = applyTtsCleanupLines(input, [removeEveryCharacterRule]);

      expect(output.length).toBe(1000);
    });

    it("should remove separator lines that include em dash and hyphen", () => {
      const rule: RegexCleanupRule = {
        id: "rule-mixed-dash",
        name: "Remove mixed dash separator",
        pattern: "/^[\\s]*[—-]{3,}[\\s]*$/gm",
        flags: "",
        enabled: true,
        appliesTo: "tts",
      };

      const input = "Intro\n—-------------------------------\nOutro";
      const output = applyTtsCleanupLines(input, [rule]);

      expect(output).toBe("Intro\n\nOutro");
    });
  });

  describe("applyDownloadCleanup", () => {
    it("should apply sentence removal before regex cleanup in HTML text nodes", () => {
      const html = "<div><p>Intro bad</p><p>=====</p><p>Outro</p></div>";
      const sentenceList = ["bad"];
      const rules: RegexCleanupRule[] = [
        {
          id: "rule-separators",
          name: "Remove separators",
          pattern: "(?:=){5,}",
          flags: "",
          enabled: true,
          appliesTo: "download",
        },
      ];

      const cleaned = applyDownloadCleanup(html, sentenceList, rules);
      expect(cleaned).toContain("Intro ");
      expect(cleaned).not.toContain("bad");
      expect(cleaned).not.toContain("=====");
      expect(cleaned).toContain("Outro");
    });

    it("should not alter script tag contents", () => {
      const html = '<div><script>var x = "=====";</script><p>=====</p></div>';
      const rules: RegexCleanupRule[] = [
        {
          id: "rule-separators",
          name: "Remove separators",
          pattern: "(?:=){5,}",
          flags: "",
          enabled: true,
          appliesTo: "download",
        },
      ];

      const cleaned = applyDownloadCleanup(html, [], rules);
      expect(cleaned).toContain('<script>var x = "=====";</script>');
      expect(cleaned).not.toContain("<p>=====</p>");
    });
  });
});
