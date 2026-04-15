import { renderHook, act } from "@testing-library/react-native";

import { useRegexRuleManagement } from "../useRegexRuleManagement";
import { RegexCleanupRule } from "../../types";
import { DEFAULT_QUICK_CONFIG } from "../../types/sentenceRemoval";

jest.mock("../../context/AlertContext");
jest.mock("../../utils/regexValidation");
jest.mock("../../utils/regexBuilder");

const mockValidateRegexCleanupRule = require("../../utils/regexValidation")
  .validateRegexCleanupRule;
const mockGenerateQuickPattern = require("../../utils/regexBuilder")
  .generateQuickPattern;
const mockParseQuickPattern = require("../../utils/regexBuilder").parseQuickPattern;
const mockGenerateRuleName = require("../../utils/regexBuilder").generateRuleName;

describe("useRegexRuleManagement", () => {
  const mockShowAlert = jest.fn();
  const mockSaveRegexRules = jest.fn().mockResolvedValue(undefined);
  const initialRules: RegexCleanupRule[] = [
    {
      id: "rule_1",
      name: "Rule One",
      pattern: "pattern1",
      flags: "g",
      enabled: true,
      appliesTo: "both",
    },
  ];

  beforeEach(() => {
    jest.clearAllMocks();
    jest.resetAllMocks();

    const { useAppAlert } = require("../../context/AlertContext");
    useAppAlert.mockReturnValue({ showAlert: mockShowAlert });

    mockValidateRegexCleanupRule.mockReturnValue({ valid: true });
    mockGenerateQuickPattern.mockReturnValue({
      pattern: "generated_pattern",
      flags: "g",
    });
    mockParseQuickPattern.mockReturnValue(null);
    mockGenerateRuleName.mockReturnValue("Generated Rule Name");
  });

  const renderTestHook = () =>
    renderHook(() => useRegexRuleManagement(initialRules, mockSaveRegexRules));

  it("initializes with default quick config pattern when in quick mode", () => {
    const { result } = renderTestHook();

    expect(result.current.dialogVisible).toBe(false);
    expect(result.current.ruleDraft).toBeDefined();
    expect(result.current.previewInput).toBe("");
    expect(result.current.effectivePattern).toBe("generated_pattern");
    expect(result.current.effectiveFlags).toBe("g");
  });

  it("opens dialog for new rule", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    expect(result.current.dialogVisible).toBe(true);
    expect(result.current.ruleDraft.mode).toBe("quick");
  });

  it("opens dialog for editing existing rule in quick mode", () => {
    mockParseQuickPattern.mockReturnValue(DEFAULT_QUICK_CONFIG);

    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog(initialRules[0]);
    });

    expect(result.current.dialogVisible).toBe(true);
    expect(result.current.ruleDraft.id).toBe("rule_1");
    expect(result.current.ruleDraft.name).toBe("Rule One");
    expect(result.current.ruleDraft.mode).toBe("quick");
  });

  it("opens dialog for editing existing rule in advanced mode", () => {
    mockParseQuickPattern.mockReturnValue(null);

    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog(initialRules[0]);
    });

    expect(result.current.dialogVisible).toBe(true);
    expect(result.current.ruleDraft.mode).toBe("advanced");
  });

  it("closes dialog and resets state", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    act(() => {
      result.current.closeDialog();
    });

    expect(result.current.dialogVisible).toBe(false);
    expect(result.current.ruleDraft.name).toBe("");
  });

  it("updates rule draft", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    act(() => {
      result.current.updateDraft({
        ...result.current.ruleDraft,
        name: "New Name",
      });
    });

    expect(result.current.ruleDraft.name).toBe("New Name");
  });

  it("updates preview input", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    act(() => {
      result.current.updatePreviewInput("test preview");
    });

    expect(result.current.previewInput).toBe("test preview");
  });

  it("derives pattern from quick config in quick mode", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    expect(mockGenerateQuickPattern).toHaveBeenCalled();
    expect(result.current.effectivePattern).toBe("generated_pattern");
  });

  it("uses raw pattern in advanced mode", () => {
    mockGenerateQuickPattern.mockClear();

    const { result } = renderTestHook();

    act(() => {
      result.current.updateDraft({
        ...result.current.ruleDraft,
        mode: "advanced",
        pattern: "raw_pattern",
      });
    });

    expect(result.current.effectivePattern).toBe("raw_pattern");
    expect(mockGenerateQuickPattern).toHaveBeenCalledTimes(1);
  });

  it("shows alert when saving invalid rule", () => {
    mockValidateRegexCleanupRule.mockReturnValue({
      valid: false,
      error: "Invalid regex",
    });

    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    act(() => {
      result.current.handleSave();
    });

    expect(mockShowAlert).toHaveBeenCalledWith(
      "Invalid Rule",
      "Invalid regex",
    );
    expect(mockSaveRegexRules).not.toHaveBeenCalled();
  });

  it("shows alert when saving duplicate rule", () => {
    mockValidateRegexCleanupRule.mockReturnValue({
      valid: true,
      normalizedPattern: "pattern1",
      normalizedFlags: "g",
    });

    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog({
        id: "rule_new",
        name: "New Rule",
        pattern: "pattern1",
        flags: "g",
        enabled: true,
        appliesTo: "both",
      });
    });

    act(() => {
      result.current.handleSave();
    });

    expect(mockShowAlert).toHaveBeenCalledWith(
      "Invalid Rule",
      "A similar regex rule already exists",
    );
    expect(mockSaveRegexRules).not.toHaveBeenCalled();
  });

  it("saves new rule at beginning of list", () => {
    mockValidateRegexCleanupRule.mockReturnValue({
      valid: true,
      normalizedPattern: "new_pattern",
      normalizedFlags: "",
    });

    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    act(() => {
      result.current.updateDraft({
        ...result.current.ruleDraft,
        name: "New Rule",
        pattern: "new_pattern",
      });
    });

    act(() => {
      result.current.handleSave();
    });

    expect(mockSaveRegexRules).toHaveBeenCalledWith(
      expect.arrayContaining([
        expect.objectContaining({ name: "New Rule", pattern: "new_pattern" }),
        expect.objectContaining({ id: "rule_1" }),
      ]),
    );
  });

  it("updates existing rule when editing", () => {
    mockValidateRegexCleanupRule.mockReturnValue({
      valid: true,
      normalizedPattern: "updated_pattern",
      normalizedFlags: "gi",
    });

    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog(initialRules[0]);
    });

    act(() => {
      result.current.updateDraft({
        ...result.current.ruleDraft,
        pattern: "updated_pattern",
        flags: "gi",
      });
    });

    act(() => {
      result.current.handleSave();
    });

    expect(mockSaveRegexRules).toHaveBeenCalledWith([
      expect.objectContaining({
        id: "rule_1",
        pattern: "updated_pattern",
        flags: "gi",
      }),
    ]);
  });

  it("generates rule name from quick config when name is empty", () => {
    mockValidateRegexCleanupRule.mockReturnValue({
      valid: true,
      normalizedPattern: "pattern",
      normalizedFlags: "",
    });

    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    act(() => {
      result.current.updateDraft({
        ...result.current.ruleDraft,
        name: "",
      });
    });

    act(() => {
      result.current.handleSave();
    });

    expect(mockGenerateRuleName).toHaveBeenCalled();
    expect(mockSaveRegexRules).toHaveBeenCalledWith(
      expect.arrayContaining([
        expect.objectContaining({ name: "Generated Rule Name" }),
      ]),
    );
  });

  it("shows delete confirmation alert", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.handleDelete("rule_1");
    });

    expect(mockShowAlert).toHaveBeenCalledWith(
      "Remove Regex Rule",
      "Are you sure you want to delete this regex cleanup rule?",
      expect.arrayContaining([
        expect.objectContaining({ text: "Cancel" }),
        expect.objectContaining({ text: "Delete", style: "destructive" }),
      ]),
    );
  });

  it("deletes rule when confirmed", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.handleDelete("rule_1");
    });

    const deleteButton = mockShowAlert.mock.calls[0][2].find(
      (btn: unknown) => (btn as { text: string }).text === "Delete",
    );

    act(() => {
      (deleteButton as { onPress: () => void }).onPress();
    });

    expect(mockSaveRegexRules).toHaveBeenCalledWith([]);
  });

  it("toggles rule enabled state", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.handleToggle("rule_1", false);
    });

    expect(mockSaveRegexRules).toHaveBeenCalledWith([
      expect.objectContaining({ id: "rule_1", enabled: false }),
    ]);
  });
});
