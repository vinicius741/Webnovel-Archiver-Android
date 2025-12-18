import { MD3DarkTheme as DefaultTheme } from 'react-native-paper';

export const DarkTheme = {
    ...DefaultTheme,
    colors: {
        ...DefaultTheme.colors,
        primary: '#818CF8',
        onPrimary: '#0F172A',
        primaryContainer: '#312E81',
        onPrimaryContainer: '#E0E7FF',
        secondary: '#94A3B8',
        onSecondary: '#0F172A',
        secondaryContainer: '#1E293B',
        onSecondaryContainer: '#F1F5F9',
        background: '#0F172A',
        surface: '#1E293B',
        surfaceVariant: '#334155',
        onSurface: '#F1F5F9',
        onSurfaceVariant: '#94A3B8',
        outline: '#475569',
        elevation: {
            ...DefaultTheme.colors.elevation,
            level1: '#1E293B',
            level2: '#242F41',
            level3: '#2A3548',
        }
    },
};
