const tseslint = require("typescript-eslint");
const js = require("@eslint/js");
const reactPlugin = require("eslint-plugin-react");
const reactHooksPlugin = require("eslint-plugin-react-hooks");
const reactNativePlugin = require("eslint-plugin-react-native");
const globals = require("globals");

module.exports = [
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  {
    files: ["src/**/*.{ts,tsx}"],
    languageOptions: {
      parser: tseslint.parser,
      parserOptions: {
        project: "./tsconfig.json",
        ecmaFeatures: {
          jsx: true,
        },
      },
      globals: {
        ...globals.jest,
      },
    },
    plugins: {
      "@typescript-eslint": tseslint.plugin,
      "react": reactPlugin,
      "react-hooks": reactHooksPlugin,
      "react-native": reactNativePlugin,
    },
    settings: {
      react: {
        version: "detect",
      },
    },
    rules: {
      "react/react-in-jsx-scope": "off",
      "react-native/no-unused-styles": "warn",
      "react-native/split-platform-components": "warn",
      "react-native/no-inline-styles": "off",
      "react-native/no-color-literals": "off",
      "@typescript-eslint/explicit-function-return-type": "off",
      "@typescript-eslint/no-explicit-any": "warn",
      "@typescript-eslint/no-unused-vars": ["warn", { "argsIgnorePattern": "^_" }],
      "@typescript-eslint/no-floating-promises": "off",
      "@typescript-eslint/no-misused-promises": "off",
      "@typescript-eslint/require-await": "off",
      "@typescript-eslint/unbound-method": "off",
      "@typescript-eslint/no-unsafe-assignment": "warn",
      "@typescript-eslint/no-unsafe-member-access": "warn",
      "@typescript-eslint/no-unsafe-call": "warn",
      "@typescript-eslint/no-unsafe-return": "warn",
      "@typescript-eslint/no-unsafe-argument": "warn",
      "no-case-declarations": "off",
      "@typescript-eslint/no-require-imports": "warn",
      "@typescript-eslint/no-unsafe-enum-comparison": "warn",
      "@typescript-eslint/ban-ts-comment": "warn",
      "@typescript-eslint/await-thenable": "warn",
      "no-useless-escape": "warn",
      ...reactHooksPlugin.configs.recommended.rules,
    },
  },
  {
    files: ["src/**/*.test.{ts,tsx}", "src/**/__tests__/**"],
    rules: {
      "@typescript-eslint/no-unsafe-assignment": "off",
      "@typescript-eslint/no-unsafe-member-access": "off",
      "@typescript-eslint/no-unsafe-call": "off",
      "@typescript-eslint/no-unsafe-return": "off",
      "@typescript-eslint/no-unsafe-argument": "off",
      "@typescript-eslint/no-require-imports": "off",
      "@typescript-eslint/await-thenable": "off",
      "@typescript-eslint/no-unsafe-enum-comparison": "off",
      "@typescript-eslint/unbound-method": "off",
      "@typescript-eslint/require-await": "off",
      "@typescript-eslint/no-floating-promises": "off",
      "@typescript-eslint/no-unsafe-function-type": "off",
      "@typescript-eslint/ban-ts-comment": "off",
    },
  },
  {
    ignores: ["node_modules/**", "dist/**", "build/**", "*.config.js"],
  },
];