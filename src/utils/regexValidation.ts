import { RegexCleanupRule } from "../types";

const MAX_REGEX_PATTERN_LENGTH = 500;
const MAX_RULE_NAME_LENGTH = 80;
const REGEX_FLAG_ORDER = ["g", "i", "m", "s", "u"] as const;
const ALLOWED_REGEX_FLAGS = new Set(REGEX_FLAG_ORDER);
const REPEATING_QUANTIFIER = "(?:\\+|\\*|\\{\\d*,?\\d*\\})";

const RISKY_REGEX_CHECKS = [
  {
    pattern: new RegExp(
      `\\((?:[^()\\\\]|\\\\.)*[+*](?:[^()\\\\]|\\\\.)*\\)\\s*${REPEATING_QUANTIFIER}`,
    ),
    reason: "Nested quantifiers can cause very slow matching.",
  },
  {
    pattern: new RegExp(
      `\\((?:[^()\\\\]|\\\\.)*\\.\\*(?:[^()\\\\]|\\\\.)*\\)\\s*${REPEATING_QUANTIFIER}`,
    ),
    reason:
      "Patterns with wildcard groups under another quantifier are not allowed.",
  },
  {
    pattern: new RegExp(
      `\\((?:[^()\\\\]|\\\\.)*\\\\\\d+(?:[^()\\\\]|\\\\.)*\\)\\s*${REPEATING_QUANTIFIER}`,
    ),
    reason: "Backreferences inside quantified groups are not allowed.",
  },
] as const;

export interface RegexValidationResult {
  valid: boolean;
  error?: string;
  normalizedPattern?: string;
  normalizedFlags?: string;
}

export interface ParsedRule {
  pattern: string;
  normalizedFlags: string;
}

export const normalizeRegexFlags = (flags: string): string => {
  const input = (flags || "").trim().toLowerCase();
  const unique = new Set(input.split(""));
  return REGEX_FLAG_ORDER.filter((flag) => unique.has(flag)).join("");
};

const detectRegexRisk = (pattern: string): string | null => {
  for (const check of RISKY_REGEX_CHECKS) {
    if (check.pattern.test(pattern)) {
      return check.reason;
    }
  }
  return null;
};

const REGEX_LITERAL_PATTERN = /^\/((?:\\.|[^\\/])*)\/([gimsu]*)$/i;

const normalizeRegexInput = (
  patternInput: string,
  flagsInput: string,
): { pattern: string; flags: string; error?: string } => {
  const trimmedPattern = (patternInput || "").trim();
  const trimmedFlags = (flagsInput || "").trim().toLowerCase();
  const maybeLiteral =
    trimmedPattern.startsWith("/") && trimmedPattern.lastIndexOf("/") > 0;
  const literalRegex = maybeLiteral
    ? /^\/((?:\\.|[^\\/])*)\/([a-z]*)$/i
    : REGEX_LITERAL_PATTERN;
  const literalMatch = trimmedPattern.match(literalRegex);

  if (!literalMatch && maybeLiteral) {
    return {
      pattern: trimmedPattern,
      flags: trimmedFlags,
      error:
        "Invalid regex literal. Use /pattern/flags or provide pattern and flags separately.",
    };
  }

  if (!literalMatch) {
    return { pattern: trimmedPattern, flags: trimmedFlags, error: undefined };
  }

  const parsedPattern = literalMatch[1];
  const literalFlags = literalMatch[2].toLowerCase();

  return {
    pattern: parsedPattern,
    flags: `${trimmedFlags}${literalFlags}`,
    error: undefined,
  };
};

export const parseRegexCleanupRule = (
  rule: Pick<RegexCleanupRule, "name" | "pattern" | "flags">,
): { parsed?: ParsedRule; error?: string } => {
  const name = (rule.name || "").trim();
  const normalizedInput = normalizeRegexInput(
    rule.pattern || "",
    rule.flags || "",
  );
  const pattern = normalizedInput.pattern;
  const flags = normalizedInput.flags;

  if (normalizedInput.error) {
    return { error: normalizedInput.error };
  }

  if (!name) {
    return { error: "Rule name is required." };
  }

  if (name.length > MAX_RULE_NAME_LENGTH) {
    return {
      error: `Rule name must be ${MAX_RULE_NAME_LENGTH} characters or fewer.`,
    };
  }

  if (!pattern) {
    return { error: "Regex pattern is required." };
  }

  if (pattern.length > MAX_REGEX_PATTERN_LENGTH) {
    return {
      error: `Regex pattern must be ${MAX_REGEX_PATTERN_LENGTH} characters or fewer.`,
    };
  }

  for (const flag of flags) {
    if (!ALLOWED_REGEX_FLAGS.has(flag as (typeof REGEX_FLAG_ORDER)[number])) {
      return {
        error: `Unsupported regex flag: "${flag}". Allowed flags: ${REGEX_FLAG_ORDER.join("")}`,
      };
    }
  }

  const riskReason = detectRegexRisk(pattern);
  if (riskReason) {
    return { error: `Unsafe regex pattern: ${riskReason}` };
  }

  const normalizedFlags = normalizeRegexFlags(flags);
  try {
    new RegExp(pattern, normalizedFlags.replace(/g/g, ""));
  } catch (error) {
    return {
      error:
        error instanceof Error
          ? `Invalid regex: ${error.message}`
          : "Invalid regex pattern.",
    };
  }

  return {
    parsed: {
      pattern,
      normalizedFlags,
    },
  };
};

export const DASH_SEPARATOR_CLASS =
  "[\\-\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u2212\\u2500\\u2501]";
const DASH_QUANTIFIER_PATTERN = /(^|[^\\])-(\{(?:\d+,\d*|\d+)\}|[+*])/g;

export const expandDashSeparatorQuantifiers = (pattern: string): string => {
  return pattern.replace(
    DASH_QUANTIFIER_PATTERN,
    `$1${DASH_SEPARATOR_CLASS}$2`,
  );
};

export const validateRegexCleanupRule = (
  rule: Pick<RegexCleanupRule, "name" | "pattern" | "flags">,
): RegexValidationResult => {
  const parsed = parseRegexCleanupRule(rule);
  if (!parsed.parsed) {
    return { valid: false, error: parsed.error || "Invalid regex rule." };
  }

  return {
    valid: true,
    normalizedPattern: parsed.parsed.pattern,
    normalizedFlags: parsed.parsed.normalizedFlags,
  };
};
