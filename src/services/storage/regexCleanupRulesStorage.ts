import AsyncStorage from "@react-native-async-storage/async-storage";
import type { RegexCleanupRule } from "../../types";
import { validateRegexCleanupRule } from "../../utils/textCleanup";
import { STORAGE_KEYS } from "./storageKeys";

const DEFAULT_REGEX_CLEANUP_RULES: RegexCleanupRule[] = [];

export interface RegexCleanupRuleRejection {
  id?: string;
  name?: string;
  reason: string;
}

interface RegexCleanupRulesSanitizeResult {
  rules: RegexCleanupRule[];
  rejected: RegexCleanupRuleRejection[];
}

export interface RegexCleanupRulesWithDiagnostics {
  rules: RegexCleanupRule[];
  rejected: RegexCleanupRuleRejection[];
}

export class RegexCleanupRulesStorage {
  private sanitizeRegexCleanupRules(
    input: unknown,
  ): RegexCleanupRulesSanitizeResult {
    if (!Array.isArray(input)) return { rules: [], rejected: [] };

    const sanitized: RegexCleanupRule[] = [];
    const rejected: RegexCleanupRuleRejection[] = [];
    for (const item of input) {
      if (!item || typeof item !== "object") {
        rejected.push({ reason: "Entry is not a valid object." });
        continue;
      }

      const raw = item as Record<string, unknown>;
      const id = typeof raw.id === "string" ? raw.id.trim() : "";
      const name = typeof raw.name === "string" ? raw.name.trim() : "";
      const pattern = typeof raw.pattern === "string" ? raw.pattern : "";
      const flags = typeof raw.flags === "string" ? raw.flags : "";
      const enabled = typeof raw.enabled === "boolean" ? raw.enabled : true;
      const appliesToRaw = raw.appliesTo;
      const appliesTo =
        appliesToRaw === "download" ||
        appliesToRaw === "tts" ||
        appliesToRaw === "both"
          ? appliesToRaw
          : "both";

      if (!id) {
        rejected.push({ name, reason: "Missing rule id." });
        continue;
      }
      const validation = validateRegexCleanupRule({ name, pattern, flags });
      if (!validation.valid) {
        rejected.push({
          id,
          name,
          reason: validation.error || "Validation failed.",
        });
        continue;
      }

      sanitized.push({
        id,
        name,
        pattern: validation.normalizedPattern || pattern.trim(),
        flags: validation.normalizedFlags || "",
        enabled,
        appliesTo,
      });
    }

    const unique = new Map<string, RegexCleanupRule>();
    sanitized.forEach((rule) => unique.set(rule.id, rule));
    return {
      rules: Array.from(unique.values()),
      rejected,
    };
  }

  async getRegexCleanupRules(): Promise<RegexCleanupRule[]> {
    const result = await this.getRegexCleanupRulesWithDiagnostics();
    return result.rules;
  }

  async getRegexCleanupRulesWithDiagnostics(): Promise<RegexCleanupRulesWithDiagnostics> {
    try {
      const jsonValue = await AsyncStorage.getItem(
        STORAGE_KEYS.REGEX_CLEANUP_RULES,
      );
      if (!jsonValue)
        return { rules: DEFAULT_REGEX_CLEANUP_RULES, rejected: [] };

      const parsed = JSON.parse(jsonValue);
      const sanitized = this.sanitizeRegexCleanupRules(parsed);

      if (sanitized.rejected.length > 0) {
        console.warn(
          `[StorageService] Skipped ${sanitized.rejected.length} invalid regex cleanup rule(s) while loading.`,
        );
      }

      return sanitized;
    } catch (e) {
      console.error("Failed to load regex cleanup rules", e);
      return { rules: DEFAULT_REGEX_CLEANUP_RULES, rejected: [] };
    }
  }

  async saveRegexCleanupRules(rules: RegexCleanupRule[]): Promise<void> {
    try {
      const sanitized = this.sanitizeRegexCleanupRules(rules);
      const jsonValue = JSON.stringify(sanitized.rules);
      await AsyncStorage.setItem(STORAGE_KEYS.REGEX_CLEANUP_RULES, jsonValue);

      if (sanitized.rejected.length > 0) {
        console.warn(
          `[StorageService] Skipped ${sanitized.rejected.length} invalid regex cleanup rule(s) while saving.`,
        );
      }
    } catch (e) {
      console.error("Failed to save regex cleanup rules", e);
    }
  }
}
