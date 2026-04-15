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

export const removeUnwantedSentences = (
  content: string,
  sentenceRemovalList: string[],
): string => {
  let cleanContent = content;
  for (const sentence of sentenceRemovalList) {
    cleanContent = cleanContent.split(sentence).join("");
  }
  return cleanContent;
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

export const applyDownloadCleanup = (
  html: string,
  sentenceRemovalList: string[],
  regexRules: RegexCleanupRule[],
): string => {
  const cleanedSentenceContent = removeUnwantedSentences(
    html,
    sentenceRemovalList,
  );
  const cleanupRunner = createRegexCleanupRunner(regexRules, "download");

  try {
    const $ = cheerio.load(cleanedSentenceContent);

    const processNode = (node: AnyNode) => {
      if (!node) return;

      if (
        node.type === ElementType.Text &&
        typeof node.data === "string" &&
        !hasSkippedAncestor(node)
      ) {
        node.data = cleanupRunner(node.data);
      }

      const children = (node as Element).children;
      if (Array.isArray(children)) {
        children.forEach(processNode);
      }
    };

    $("body")
      .contents()
      .each((_, node) => processNode(node));
    return $("body").html() || cleanedSentenceContent;
  } catch (error) {
    console.error("[TextCleanup] Failed to apply download cleanup.", error);
    return cleanedSentenceContent;
  }
};
