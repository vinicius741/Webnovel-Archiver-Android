import { QuickBuilderConfig } from '../types/sentenceRemoval';

const SPECIAL_CHARS = /[.*+?^${}()|[\]\\]/g;

export function escapeRegex(str: string): string {
    return str.replace(SPECIAL_CHARS, '\\$&');
}

export function unescapeRegex(str: string): string {
    return str.replace(/\\([.*+?^${}()|[\]\\])/g, '$1');
}

export function generateQuickPattern(config: QuickBuilderConfig): { pattern: string; flags: string } {
    const { characters, minCount, wholeLine } = config;
    
    if (!characters || minCount < 1) {
        return { pattern: '', flags: '' };
    }
    
    const escaped = escapeRegex(characters);
    const isMultiChar = characters.length > 1;
    
    let corePattern: string;
    if (isMultiChar) {
        corePattern = `(?:${escaped}){${minCount},}`;
    } else {
        corePattern = `${escaped}{${minCount},}`;
    }
    
    if (wholeLine) {
        return {
            pattern: `^[\\s]*${corePattern}[\\s]*$`,
            flags: 'gm'
        };
    }
    
    return {
        pattern: corePattern,
        flags: 'g'
    };
}

export function generateRuleName(config: QuickBuilderConfig): string {
    const { characters, minCount, wholeLine } = config;
    
    if (!characters) {
        return '';
    }
    
    const displayChars = characters.length > 4 
        ? `${characters.slice(0, 4)}...` 
        : characters;
    
    const scopeText = wholeLine ? 'separator lines' : 'patterns';
    
    return `Remove ${displayChars} (${minCount}+) ${scopeText}`;
}

export function parseQuickPattern(pattern: string, flags: string): QuickBuilderConfig | null {
    // Matches whole-line patterns like: ^[\s]*-{3,}[\s]*$
    // Breakdown: ^ (start) + [\s]* (optional leading whitespace) + (captured core) + [\s]* (trailing whitespace) + $ (end)
    const wholeLineRegex = /^\^\[\\s\]\*(.+)\[\\s\]\*\$$/;
    
    // Matches simple single-char patterns like: ={5,} or \.{3,}
    // Captures: (escaped char) + {minCount,}
    const simpleRegex = /^(.+)\{(\d+),\}$/;
    
    // Matches multi-char patterns like: (?:##){3,} or (?:\*\*){5,}
    // Captures: (?:escaped chars) + {minCount,}
    const multiCharRegex = /^\(\?:(.+)\)\{(\d+),\}$/;
    
    let corePattern = pattern;
    let wholeLine = false;
    
    const wholeLineMatch = pattern.match(wholeLineRegex);
    if (wholeLineMatch) {
        wholeLine = true;
        corePattern = wholeLineMatch[1];
        
        if (flags !== 'gm') {
            return null;
        }
    }
    
    let characters = '';
    let minCount = 0;
    
    const multiMatch = corePattern.match(multiCharRegex);
    if (multiMatch) {
        characters = unescapeRegex(multiMatch[1]);
        minCount = parseInt(multiMatch[2], 10);
        return { characters, minCount, wholeLine };
    }
    
    const singleMatch = corePattern.match(simpleRegex);
    if (singleMatch) {
        characters = unescapeRegex(singleMatch[1]);
        minCount = parseInt(singleMatch[2], 10);
        return { characters, minCount, wholeLine };
    }
    
    return null;
}

export function isQuickBuilderPattern(pattern: string, flags: string): boolean {
    return parseQuickPattern(pattern, flags) !== null;
}
