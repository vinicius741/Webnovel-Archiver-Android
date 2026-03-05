import { renderHook, act } from "@testing-library/react-native";
import * as Sharing from "expo-sharing";

import { useExportRules } from "../useExportRules";
import { RegexCleanupRule } from "../../types";

jest.mock("expo-file-system", () => ({
  File: jest.fn().mockImplementation(() => ({
    exists: true,
    create: jest.fn(),
    write: jest.fn(),
  })),
  Paths: {
    cache: "/cache",
  },
}));
jest.mock("expo-sharing");
jest.mock("../../context/AlertContext");

describe("useExportRules", () => {
  const mockShowAlert = jest.fn();
  let MockFile: jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();

    const { useAppAlert } = require("../../context/AlertContext");
    useAppAlert.mockReturnValue({ showAlert: mockShowAlert });

    const fs = require("expo-file-system");
    MockFile = fs.File as jest.Mock;
    const mockFileInstance = {
      exists: true,
      uri: "file:///cache/text_cleanup_rules.json",
      create: jest.fn(),
      write: jest.fn(),
    };
    MockFile.mockImplementation(() => mockFileInstance);

    (Sharing.isAvailableAsync as jest.Mock).mockResolvedValue(true);
    (Sharing.shareAsync as jest.Mock).mockResolvedValue(undefined);
  });

  const renderTestHook = () => renderHook(() => useExportRules());

  it("exports rules to JSON file and shares", async () => {
    const { result } = renderTestHook();

    const sentences = ["sentence 1", "sentence 2"];
    const rules: RegexCleanupRule[] = [
      {
        id: "rule_1",
        name: "Rule 1",
        pattern: "pattern",
        flags: "g",
        enabled: true,
        appliesTo: "both",
      },
    ];

    await act(async () => {
      await result.current.exportRules(sentences, rules);
    });

    expect(MockFile).toHaveBeenCalledWith(expect.anything(), "text_cleanup_rules.json");
    const mockFileInstance = (MockFile).mock.results[0].value;
    expect(mockFileInstance.write).toHaveBeenCalledWith(
      JSON.stringify({ sentences, regexRules: rules }, null, 4),
    );
    expect(Sharing.isAvailableAsync).toHaveBeenCalled();
    expect(Sharing.shareAsync).toHaveBeenCalledWith(
      "file:///cache/text_cleanup_rules.json",
      {
        mimeType: "application/json",
        dialogTitle: "Export Text Cleanup Rules",
        UTI: "public.json",
      },
    );
  });

  it("shows error when sharing is not available", async () => {
    (Sharing.isAvailableAsync as jest.Mock).mockResolvedValue(false);

    const { result } = renderTestHook();

    await act(async () => {
      await result.current.exportRules([], []);
    });

    expect(Sharing.shareAsync).not.toHaveBeenCalled();
    expect(mockShowAlert).toHaveBeenCalledWith(
      "Error",
      "Sharing is not available on this device",
    );
  });

  it("shows error alert on export failure", async () => {
    const consoleSpy = jest.spyOn(console, "error").mockImplementation();
    MockFile.mockImplementation(() => {
      throw new Error("Share failed");
    });

    const { result } = renderTestHook();

    await act(async () => {
      await result.current.exportRules([], []);
    });

    expect(consoleSpy).toHaveBeenCalledWith(new Error("Share failed"));
    expect(mockShowAlert).toHaveBeenCalledWith(
      "Export Failed",
      "Failed to export the file.",
    );

    consoleSpy.mockRestore();
  });

  it("creates file if it does not exist", async () => {
    const mockFileInstance = {
      exists: false,
      uri: "file:///cache/text_cleanup_rules.json",
      create: jest.fn(),
      write: jest.fn(),
    };
    MockFile.mockImplementation(() => mockFileInstance);

    const { result } = renderTestHook();

    await act(async () => {
      await result.current.exportRules([], []);
    });

    expect(mockFileInstance.create).toHaveBeenCalled();
    expect(mockFileInstance.write).toHaveBeenCalled();
  });
});
