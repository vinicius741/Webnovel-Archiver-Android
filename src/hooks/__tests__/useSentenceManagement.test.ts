import { renderHook, act } from "@testing-library/react-native";

import { useSentenceManagement } from "../useSentenceManagement";

jest.mock("../../context/AlertContext");

describe("useSentenceManagement", () => {
  const mockShowAlert = jest.fn();
  const mockSaveSentences = jest.fn().mockResolvedValue(undefined);
  const initialSentences = ["sentence one", "sentence two"];

  beforeEach(() => {
    jest.clearAllMocks();
    const { useAppAlert } = require("../../context/AlertContext");
    useAppAlert.mockReturnValue({ showAlert: mockShowAlert });
  });

  const renderTestHook = () =>
    renderHook(() => useSentenceManagement(initialSentences, mockSaveSentences));

  it("initializes with dialog closed and empty sentence", () => {
    const { result } = renderTestHook();

    expect(result.current.dialogVisible).toBe(false);
    expect(result.current.sentence).toBe("");
    expect(result.current.editingIndex).toBeNull();
    expect(result.current.isEditing).toBe(false);
  });

  it("opens dialog for new sentence", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    expect(result.current.dialogVisible).toBe(true);
    expect(result.current.sentence).toBe("");
    expect(result.current.editingIndex).toBeNull();
    expect(result.current.isEditing).toBe(false);
  });

  it("opens dialog for editing existing sentence", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog("existing sentence", 1);
    });

    expect(result.current.dialogVisible).toBe(true);
    expect(result.current.sentence).toBe("existing sentence");
    expect(result.current.editingIndex).toBe(1);
    expect(result.current.isEditing).toBe(true);
  });

  it("closes dialog and resets state", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog("test", 0);
    });

    act(() => {
      result.current.closeDialog();
    });

    expect(result.current.dialogVisible).toBe(false);
    expect(result.current.sentence).toBe("");
    expect(result.current.editingIndex).toBeNull();
  });

  it("updates sentence value", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    act(() => {
      result.current.updateSentence("new sentence text");
    });

    expect(result.current.sentence).toBe("new sentence text");
  });

  it("shows alert when saving empty sentence", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    act(() => {
      result.current.handleSave("");
    });

    expect(mockShowAlert).toHaveBeenCalledWith(
      "Invalid Sentence",
      "Sentence cannot be empty",
    );
    expect(mockSaveSentences).not.toHaveBeenCalled();
  });

  it("shows alert when saving duplicate sentence", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    act(() => {
      result.current.handleSave("sentence one");
    });

    expect(mockShowAlert).toHaveBeenCalledWith(
      "Invalid Sentence",
      "This sentence is already in the removal list",
    );
    expect(mockSaveSentences).not.toHaveBeenCalled();
  });

  it("allows saving duplicate when editing same index", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog("sentence one", 0);
    });

    act(() => {
      result.current.handleSave("sentence one");
    });

    expect(mockShowAlert).not.toHaveBeenCalled();
    expect(mockSaveSentences).toHaveBeenCalledWith(["sentence one", "sentence two"]);
  });

  it("adds new sentence at beginning of list", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    act(() => {
      result.current.handleSave("new sentence");
    });

    expect(mockSaveSentences).toHaveBeenCalledWith([
      "new sentence",
      "sentence one",
      "sentence two",
    ]);
  });

  it("updates existing sentence at specific index", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog("sentence one", 0);
    });

    act(() => {
      result.current.handleSave("updated sentence");
    });

    expect(mockSaveSentences).toHaveBeenCalledWith([
      "updated sentence",
      "sentence two",
    ]);
  });

  it("trims whitespace from sentence before saving", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.openDialog();
    });

    act(() => {
      result.current.handleSave("  trimmed sentence  ");
    });

    expect(mockSaveSentences).toHaveBeenCalledWith([
      "trimmed sentence",
      "sentence one",
      "sentence two",
    ]);
  });

  it("shows delete confirmation alert", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.handleDelete(0);
    });

    expect(mockShowAlert).toHaveBeenCalledWith(
      "Remove Sentence",
      "Are you sure you want to remove this sentence from the blocklist?",
      expect.arrayContaining([
        expect.objectContaining({ text: "Cancel" }),
        expect.objectContaining({ text: "Delete", style: "destructive" }),
      ]),
    );
  });

  it("deletes sentence when confirmed", () => {
    const { result } = renderTestHook();

    act(() => {
      result.current.handleDelete(0);
    });

    const deleteButton = mockShowAlert.mock.calls[0][2].find(
      (btn: any) => btn.text === "Delete",
    );

    act(() => {
      deleteButton.onPress();
    });

    expect(mockSaveSentences).toHaveBeenCalledWith(["sentence two"]);
  });
});
