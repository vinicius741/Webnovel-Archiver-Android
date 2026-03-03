import { RegexCleanupAppliesTo } from './index';

export type TabValue = 'sentences' | 'regex';

export type RuleMode = 'quick' | 'advanced';

export interface QuickBuilderConfig {
  characters: string;
  minCount: number;
  wholeLine: boolean;
}

export const DEFAULT_QUICK_CONFIG: QuickBuilderConfig = {
  characters: '',
  minCount: 3,
  wholeLine: true,
};

export interface RuleDraft {
  id?: string;
  name: string;
  pattern: string;
  flags: string;
  enabled: boolean;
  appliesTo: RegexCleanupAppliesTo;
  quickConfig?: QuickBuilderConfig;
  mode: RuleMode;
  showAdvanced?: boolean;
}

export const EMPTY_RULE_DRAFT: RuleDraft = {
  name: '',
  pattern: '',
  flags: '',
  enabled: true,
  appliesTo: 'both',
  quickConfig: DEFAULT_QUICK_CONFIG,
  mode: 'quick',
};

export const TARGET_LABEL_MAP: Record<RegexCleanupAppliesTo, string> = {
  both: 'Download + TTS',
  download: 'Download only',
  tts: 'TTS only',
};

export const createRuleId = (): string => {
  return `rule_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
};
