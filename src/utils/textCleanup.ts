import * as cheerio from "cheerio";
import { ElementType } from "domelementtype";
import { RegexCleanupRule } from "../types";
import {
  parseRegexCleanupRule,
  expandDashSeparatorQuantifiers,
} from "./regexValidation";

type AnyNode = import("domhandler").AnyNode;
type Element = import("domhandler").Element;

export { validateRegexCleanupRule } from "./regexValidation";
export type { RegexValidationResult } from "./regexValidation";

const MAX_TOTAL_REPLACEMENTS = 5000;
const SKIP_TAGS = new Set(["script", "style", "noscript", "iframe"]);

interface CompiledRule {
  ruleId: string;
  regex: RegExp;
}

type CleanupTarget = "download" | "tts";

const escapeRegex = (str: string): string =>
  str.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

const SINGLE_QUOTE_RE = /['\u2018\u2019]/g;
const DOUBLE_QUOTE_RE = /["\u201C\u201D]/g;
const SINGLE_QUOTE_CLASS = "['\\u2018\\u2019]";
const DOUBLE_QUOTE_CLASS = '["\\u201C\\u201D"]';

const buildSentencePattern = (sentence: string): RegExp | null => {
  const trimmed = sentence.trim();
  if (!trimmed) return null;
  let escaped = escapeRegex(trimmed);
  escaped = escaped.replace(SINGLE_QUOTE_RE, SINGLE_QUOTE_CLASS);
  escaped = escaped.replace(DOUBLE_QUOTE_RE, DOUBLE_QUOTE_CLASS);
  escaped = escaped.replace(/ /g, "\\s+");
  try {
    return new RegExp(escaped, "gi");
  } catch {
    return null;
  }
};

const compileSentencePatterns = (
  sentenceRemovalList: string[],
): RegExp[] => {
  const patterns: RegExp[] = [];
  for (const sentence of sentenceRemovalList) {
    const pattern = buildSentencePattern(sentence);
    if (pattern) patterns.push(pattern);
  }
  return patterns;
};

const applySentencePatternsToText = (
  text: string,
  patterns: RegExp[],
): { text: string; removed: number } => {
  let result = text;
  let removed = 0;
  for (const pattern of patterns) {
    pattern.lastIndex = 0;
    result = result.replace(pattern, () => {
      removed++;
      return "";
    });
  }
  return { text: result, removed };
};

export const removeUnwantedSentences = (
  content: string,
  sentenceRemovalList: string[],
): string => {
  if (!content || !sentenceRemovalList.length) return content;
  const patterns = compileSentencePatterns(sentenceRemovalList);
  return applySentencePatternsToText(content, patterns).text;
};

const isRuleApplicable = (
  rule: RegexCleanupRule,
  target: CleanupTarget,
): boolean => {
  return (
    rule.enabled && (rule.appliesTo === "both" || rule.appliesTo === target)
  );
};

const compileRules = (
  rules: RegexCleanupRule[],
  target: CleanupTarget,
): CompiledRule[] => {
  const compiled: CompiledRule[] = [];

  for (const rule of rules) {
    if (!isRuleApplicable(rule, target)) continue;

    const parsed = parseRegexCleanupRule(rule);
    if (!parsed.parsed) {
      continue;
    }
    const flagsWithGlobal = parsed.parsed.normalizedFlags.includes("g")
      ? parsed.parsed.normalizedFlags
      : `${parsed.parsed.normalizedFlags}g`;
    const patternForCompile = expandDashSeparatorQuantifiers(
      parsed.parsed.pattern,
    );

    try {
      compiled.push({
        ruleId: rule.id,
        regex: new RegExp(patternForCompile, flagsWithGlobal),
      });
    } catch (error) {
      console.error(`[TextCleanup] Failed to compile rule ${rule.id}`, error);
    }
  }

  return compiled;
};

const applyCompiledRulesToLine = (
  line: string,
  compiledRules: CompiledRule[],
  replacementState: { total: number },
): string => {
  if (replacementState.total >= MAX_TOTAL_REPLACEMENTS) {
    return line;
  }

  let updated = line;

  for (const compiledRule of compiledRules) {
    if (replacementState.total >= MAX_TOTAL_REPLACEMENTS) {
      break;
    }

    try {
      compiledRule.regex.lastIndex = 0;
      updated = updated.replace(compiledRule.regex, (match) => {
        if (replacementState.total >= MAX_TOTAL_REPLACEMENTS) {
          return match;
        }

        replacementState.total += 1;
        return "";
      });
    } catch (error) {
      console.error(
        `[TextCleanup] Failed to apply rule ${compiledRule.ruleId}`,
        error,
      );
    }
  }

  return updated;
};

const cleanTextWithCompiledRules = (
  text: string,
  compiledRules: CompiledRule[],
  replacementState: { total: number },
): string => {
  if (!text || compiledRules.length === 0) return text;

  const lines = text.replace(/\r\n/g, "\n").split("\n");
  const cleanedLines = lines.map((line) =>
    applyCompiledRulesToLine(line, compiledRules, replacementState),
  );
  return cleanedLines.join("\n");
};

export const createRegexCleanupRunner = (
  regexRules: RegexCleanupRule[],
  target: CleanupTarget,
): ((text: string) => string) => {
  const compiledRules = compileRules(regexRules, target);
  if (compiledRules.length === 0) {
    return (text: string) => text;
  }

  const replacementState = { total: 0 };
  return (text: string) =>
    cleanTextWithCompiledRules(text, compiledRules, replacementState);
};

export const applyTtsCleanupLines = (
  text: string,
  regexRules: RegexCleanupRule[],
): string => {
  return createRegexCleanupRunner(regexRules, "tts")(text);
};

const hasSkippedAncestor = (node: AnyNode): boolean => {
  let current = node.parent;
  while (current) {
    if (
      current.type === ElementType.Tag ||
      current.type === ElementType.Script ||
      current.type === ElementType.Style
    ) {
      const tagName = String(current.name || "").toLowerCase();
      if (SKIP_TAGS.has(tagName)) {
        return true;
      }
    }
    current = current.parent;
  }
  return false;
};

export interface CleanupResult {
  html: string;
  sentencesRemoved: number;
}

export const applyDownloadCleanup = (
  html: string,
  sentenceRemovalList: string[],
  regexRules: RegexCleanupRule[],
): CleanupResult => {
  const sentencePatterns = compileSentencePatterns(sentenceRemovalList);
  const cleanupRunner = createRegexCleanupRunner(regexRules, "download");
  let totalSentencesRemoved = 0;

  try {
    const $ = cheerio.load(html);

    const processNode = (node: AnyNode) => {
      if (!node) return;

      if (
        node.type === ElementType.Text &&
        typeof node.data === "string" &&
        !hasSkippedAncestor(node)
      ) {
        const { text, removed } = applySentencePatternsToText(
          node.data,
          sentencePatterns,
        );
        totalSentencesRemoved += removed;
        node.data = cleanupRunner(text);
      }

      const children = (node as Element).children;
      if (Array.isArray(children)) {
        children.forEach(processNode);
      }
    };

    $("body")
      .contents()
      .each((_, node) => processNode(node));
    return {
      html: $("body").html() || html,
      sentencesRemoved: totalSentencesRemoved,
    };
  } catch (error) {
    console.error("[TextCleanup] Failed to apply download cleanup.", error);
    return { html, sentencesRemoved: 0 };
  }
};
