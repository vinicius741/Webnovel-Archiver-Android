# AGENTS.md

This file provides guidelines for agentic coding assistants working on this codebase.

## Build/Lint/Test Commands

### Available Scripts
- `npm test` - Run all Jest tests
- `npm start` - Start Expo development server
- `npm run android` - Run on Android device/emulator
- `npm run ios` - Run on iOS device/simulator
- `npm run web` - Run web version

### Running Single Tests
- `npm test -- src/services/__tests__/EpubGenerator.test.ts` - Run specific test file
- `npm test -- --testPathPattern=EpubGenerator` - Run tests matching pattern
- `npm test -- --testNamePattern="should generate epub"` - Run tests matching name

No lint command is configured in package.json. TypeScript is configured with strict mode.

## Code Style Guidelines

### TypeScript & Types
- Strict TypeScript mode is enabled
- Define interfaces for all component props and complex objects
- Use `as const` for enum-like objects (e.g., `DownloadStatus`)
- Export types from `src/types/index.ts` for shared types
- Mark optional fields with `?`

### Imports
- Order: React first, then third-party libraries, then internal modules
- Use absolute imports from `src/` for internal modules
- Named exports preferred for components, services, and utilities

### Components
- Functional components with TypeScript interfaces for props
- Props interface defined inline before component
- Use React hooks for state and side effects
- Named exports (e.g., `export const StoryCard = ...`)
- Styles defined at bottom with `StyleSheet.create()`

### Naming Conventions
- Components: PascalCase (`StoryCard`, `TTSController`)
- Hooks: camelCase with `use` prefix (`useAddStory`, `useStoryDetails`)
- Services/Modules: camelCase (`epubGenerator`, `storageService`)
- Interfaces/Types: PascalCase (`Story`, `Chapter`, `NovelMetadata`)
- Constants/Enums: PascalCase (`DownloadStatus`)
- Functions/Variables: camelCase (`sanitizeTitle`, `handleAdd`)
- Test files: `.test.ts` extension in `__tests__/` directories

### File Organization
- `src/components/` - UI components (grouped by feature)
- `src/hooks/` - Custom React hooks
- `src/services/` - Business logic and external integrations
  - `services/download/` - Download management
  - `services/source/` - Novel source providers
  - `services/storage/` - File system operations
- `src/types/` - TypeScript type definitions
- `src/utils/` - Pure utility functions
- `src/context/` - React contexts
- `src/theme/` - Theme configurations
- Tests co-located in `__tests__/` directories alongside implementation

### Error Handling
- Use try-catch blocks for async operations
- Log errors with `console.error()`
- Provide user-friendly messages through AlertContext or notifications
- Graceful degradation (e.g., check `Constants.executionEnvironment` for Expo Go)

### Testing
- Jest with jest-expo preset
- Mock external dependencies (fileSystem, StorageService, native modules)
- Clear mocks in `beforeEach()`
- Describe blocks to group related tests
- Test names should describe expected behavior ("should generate epub")
- Test both success and error cases

### Provider Pattern
- Implement `SourceProvider` interface for novel sources
- Methods: `isSource()`, `getStoryId()`, `parseMetadata()`, `getChapterList()`, `parseChapterContent()`
- Register providers in `SourceRegistry`

### Additional Notes
- No comments added unless necessary (minimal comment approach)
- Use `expo-router` for navigation
- Use `react-native-paper` for UI components
- Use `expo-constants` to detect runtime environment
- Cheerio for HTML parsing in providers
