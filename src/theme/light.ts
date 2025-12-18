import { MD3LightTheme as DefaultTheme } from 'react-native-paper';

export const LightTheme = {
    ...DefaultTheme,
    colors: {
        ...DefaultTheme.colors,
        primary: '#4F46E5',
        onPrimary: '#FFFFFF',
        primaryContainer: '#E0E7FF',
        onPrimaryContainer: '#312E81',
        secondary: '#0F172A',
        onSecondary: '#FFFFFF',
        secondaryContainer: '#F1F5F9',
        onSecondaryContainer: '#0F172A',
        background: '#FAFAFA',
        surface: '#FFFFFF',
        surfaceVariant: '#F1F5F9',
        onSurface: '#0F172A',
        onSurfaceVariant: '#64748B',
        outline: '#CBD5E1',
    },
};
