/**
 * Sanitizes titles by removing trailing ellipsis or multiple dots.
 * Common in some web novel sites where titles are truncated in lists.
 */
export const sanitizeTitle = (title: string): string => {
    if (!title) return '';
    // Trim first to ensure $ matches the actual content end, 
    // then remove 2+ dots OR ellipsis characters.
    return title.trim().replace(/\s*(\.{2,}|…|⋯|⋮)$/, '').trim();
};
