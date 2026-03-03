import { RegexCleanupAppliesTo } from './index';

export type TabValue = 'sentences' | 'regex';

export interface RuleDraft {
  id?: string;
  name: string;
  pattern: string;
  flags: string;
  enabled: boolean;
  appliesTo: RegexCleanupAppliesTo;
}

export const EMPTY_RULE_DRAFT: RuleDraft = {
  name: '',
  pattern: '',
  flags: '',
  enabled: true,
  appliesTo: 'both',
};

export const TARGET_LABEL_MAP: Record<RegexCleanupAppliesTo, string> = {
  both: 'Download + TTS',
  download: 'Download only',
  tts: 'TTS only',
};

export const createRuleId = (): string => {
  return `rule_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
};
