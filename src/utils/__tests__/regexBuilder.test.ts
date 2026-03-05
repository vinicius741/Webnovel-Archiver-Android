import {
  generateQuickPattern,
  generateRuleName,
  parseQuickPattern,
  escapeRegex,
  unescapeRegex,
  isQuickBuilderPattern,
} from "../regexBuilder";

describe("regexBuilder", () => {
  describe("escapeRegex", () => {
    it("should escape special regex characters", () => {
      expect(escapeRegex(".")).toBe("\\.");
      expect(escapeRegex("*")).toBe("\\*");
      expect(escapeRegex("+")).toBe("\\+");
      expect(escapeRegex("?")).toBe("\\?");
      expect(escapeRegex("^")).toBe("\\^");
      expect(escapeRegex("$")).toBe("\\$");
      expect(escapeRegex("{")).toBe("\\{");
      expect(escapeRegex("}")).toBe("\\}");
      expect(escapeRegex("(")).toBe("\\(");
      expect(escapeRegex(")")).toBe("\\)");
      expect(escapeRegex("[")).toBe("\\[");
      expect(escapeRegex("]")).toBe("\\]");
      expect(escapeRegex("\\")).toBe("\\\\");
      expect(escapeRegex("|")).toBe("\\|");
    });

    it("should not escape normal characters", () => {
      expect(escapeRegex("abc")).toBe("abc");
      expect(escapeRegex("123")).toBe("123");
      expect(escapeRegex("-")).toBe("-");
      expect(escapeRegex("=")).toBe("=");
    });
  });

  describe("unescapeRegex", () => {
    it("should unescape special regex characters", () => {
      expect(unescapeRegex("\\.")).toBe(".");
      expect(unescapeRegex("\\*")).toBe("*");
      expect(unescapeRegex("\\+")).toBe("+");
      expect(unescapeRegex("\\?")).toBe("?");
      expect(unescapeRegex("\\^")).toBe("^");
      expect(unescapeRegex("\\$")).toBe("$");
      expect(unescapeRegex("\\{")).toBe("{");
      expect(unescapeRegex("\\}")).toBe("}");
      expect(unescapeRegex("\\(")).toBe("(");
      expect(unescapeRegex("\\)")).toBe(")");
      expect(unescapeRegex("\\[")).toBe("[");
      expect(unescapeRegex("\\]")).toBe("]");
      expect(unescapeRegex("\\\\")).toBe("\\");
      expect(unescapeRegex("\\|")).toBe("|");
    });

    it("should not modify non-escaped characters", () => {
      expect(unescapeRegex("abc")).toBe("abc");
      expect(unescapeRegex("123")).toBe("123");
    });

    it("should handle mixed escaped and non-escaped", () => {
      expect(unescapeRegex("\\.\\*\\+")).toBe(".*+");
    });
  });

  describe("generateQuickPattern", () => {
    it("should generate pattern for single character", () => {
      const result = generateQuickPattern({
        characters: "=",
        minCount: 5,
        wholeLine: false,
      });
      expect(result.pattern).toBe("={5,}");
      expect(result.flags).toBe("g");
    });

    it("should generate pattern for single character with whole line", () => {
      const result = generateQuickPattern({
        characters: "-",
        minCount: 3,
        wholeLine: true,
      });
      expect(result.pattern).toBe("^[\\s]*-{3,}[\\s]*$");
      expect(result.flags).toBe("gm");
    });

    it("should generate pattern for multi-character separator", () => {
      const result = generateQuickPattern({
        characters: "##",
        minCount: 3,
        wholeLine: false,
      });
      expect(result.pattern).toBe("(?:##){3,}");
      expect(result.flags).toBe("g");
    });

    it("should generate pattern for multi-character with whole line", () => {
      const result = generateQuickPattern({
        characters: "**",
        minCount: 5,
        wholeLine: true,
      });
      expect(result.pattern).toBe("^[\\s]*(?:\\*\\*){5,}[\\s]*$");
      expect(result.flags).toBe("gm");
    });

    it("should escape special characters", () => {
      const result = generateQuickPattern({
        characters: ".",
        minCount: 3,
        wholeLine: false,
      });
      expect(result.pattern).toBe("\\.{3,}");
    });

    it("should return empty for invalid input", () => {
      expect(
        generateQuickPattern({ characters: "", minCount: 3, wholeLine: false })
          .pattern,
      ).toBe("");
      expect(
        generateQuickPattern({ characters: "=", minCount: 0, wholeLine: false })
          .pattern,
      ).toBe("");
    });
  });

  describe("generateRuleName", () => {
    it("should generate descriptive name for single character", () => {
      const name = generateRuleName({
        characters: "=",
        minCount: 5,
        wholeLine: true,
      });
      expect(name).toBe("Remove = (5+) separator lines");
    });

    it("should generate name for inline pattern", () => {
      const name = generateRuleName({
        characters: "-",
        minCount: 3,
        wholeLine: false,
      });
      expect(name).toBe("Remove - (3+) patterns");
    });

    it("should truncate long character strings", () => {
      const name = generateRuleName({
        characters: "####",
        minCount: 2,
        wholeLine: true,
      });
      expect(name).toBe("Remove #### (2+) separator lines");
    });

    it("should truncate very long character strings", () => {
      const name = generateRuleName({
        characters: "######",
        minCount: 2,
        wholeLine: true,
      });
      expect(name).toBe("Remove ####... (2+) separator lines");
    });

    it("should return empty for no characters", () => {
      const name = generateRuleName({
        characters: "",
        minCount: 3,
        wholeLine: true,
      });
      expect(name).toBe("");
    });
  });

  describe("parseQuickPattern", () => {
    it("should parse simple single character pattern", () => {
      const result = parseQuickPattern("={5,}", "g");
      expect(result).toEqual({
        characters: "=",
        minCount: 5,
        wholeLine: false,
      });
    });

    it("should parse whole line pattern", () => {
      const result = parseQuickPattern("^[\\s]*-{3,}[\\s]*$", "gm");
      expect(result).toEqual({ characters: "-", minCount: 3, wholeLine: true });
    });

    it("should parse multi-character pattern", () => {
      const result = parseQuickPattern("(?:##){4,}", "g");
      expect(result).toEqual({
        characters: "##",
        minCount: 4,
        wholeLine: false,
      });
    });

    it("should parse multi-character whole line pattern", () => {
      const result = parseQuickPattern("^[\\s]*(?:\\*\\*){5,}[\\s]*$", "gm");
      expect(result).toEqual({
        characters: "**",
        minCount: 5,
        wholeLine: true,
      });
    });

    it("should unescape special characters", () => {
      const result = parseQuickPattern("\\.{3,}", "g");
      expect(result).toEqual({
        characters: ".",
        minCount: 3,
        wholeLine: false,
      });
    });

    it("should return null for non-matching patterns", () => {
      expect(parseQuickPattern("[a-z]+", "g")).toBeNull();
      expect(parseQuickPattern("^\\d+$", "g")).toBeNull();
      expect(parseQuickPattern("", "g")).toBeNull();
    });

    it("should return null for wrong flags on whole line", () => {
      expect(parseQuickPattern("^[\\s]*-{3,}[\\s]*$", "g")).toBeNull();
    });
  });

  describe("isQuickBuilderPattern", () => {
    it("should return true for quick builder patterns", () => {
      expect(isQuickBuilderPattern("={5,}", "g")).toBe(true);
      expect(isQuickBuilderPattern("^[\\s]*-{3,}[\\s]*$", "gm")).toBe(true);
      expect(isQuickBuilderPattern("(?:##){3,}", "g")).toBe(true);
    });

    it("should return false for non-quick builder patterns", () => {
      expect(isQuickBuilderPattern("[a-z]+", "g")).toBe(false);
      expect(isQuickBuilderPattern("^\\d+$", "gm")).toBe(false);
      expect(isQuickBuilderPattern("foo|bar", "g")).toBe(false);
    });
  });

  describe("round-trip generation and parsing", () => {
    it("should round-trip single character simple pattern", () => {
      const config = { characters: "=", minCount: 5, wholeLine: false };
      const generated = generateQuickPattern(config);
      const parsed = parseQuickPattern(generated.pattern, generated.flags);
      expect(parsed).toEqual(config);
    });

    it("should round-trip single character whole line pattern", () => {
      const config = { characters: "-", minCount: 3, wholeLine: true };
      const generated = generateQuickPattern(config);
      const parsed = parseQuickPattern(generated.pattern, generated.flags);
      expect(parsed).toEqual(config);
    });

    it("should round-trip multi-character pattern", () => {
      const config = { characters: "##", minCount: 4, wholeLine: false };
      const generated = generateQuickPattern(config);
      const parsed = parseQuickPattern(generated.pattern, generated.flags);
      expect(parsed).toEqual(config);
    });

    it("should round-trip multi-character whole line pattern", () => {
      const config = { characters: "**", minCount: 5, wholeLine: true };
      const generated = generateQuickPattern(config);
      const parsed = parseQuickPattern(generated.pattern, generated.flags);
      expect(parsed).toEqual(config);
    });

    it("should round-trip pattern with special characters", () => {
      const config = { characters: ".", minCount: 3, wholeLine: false };
      const generated = generateQuickPattern(config);
      const parsed = parseQuickPattern(generated.pattern, generated.flags);
      expect(parsed).toEqual(config);
    });
  });
});
