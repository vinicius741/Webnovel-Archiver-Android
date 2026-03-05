import { useCallback, useState } from "react";
import { useAppAlert } from "../context/AlertContext";

export interface SentenceManagementState {
  dialogVisible: boolean;
  sentence: string;
  editingIndex: number | null;
}

export function useSentenceManagement(
  sentences: string[],
  saveSentences: (list: string[]) => Promise<void>,
) {
  const { showAlert } = useAppAlert();
  const [state, setState] = useState<SentenceManagementState>({
    dialogVisible: false,
    sentence: "",
    editingIndex: null,
  });

  const openDialog = useCallback(
    (sentence: string = "", index: number | null = null) => {
      setState({ dialogVisible: true, sentence, editingIndex: index });
    },
    [],
  );

  const closeDialog = useCallback(() => {
    setState({ dialogVisible: false, sentence: "", editingIndex: null });
  }, []);

  const updateSentence = useCallback((sentence: string) => {
    setState((prev) => ({ ...prev, sentence }));
  }, []);

  const validateSentence = useCallback(
    (sentence: string): { valid: boolean; error?: string } => {
      const trimmed = sentence.trim();
      if (!trimmed) {
        return { valid: false, error: "Sentence cannot be empty" };
      }
      const existingIndex = sentences.indexOf(trimmed);
      if (
        existingIndex !== -1 &&
        (state.editingIndex === null || existingIndex !== state.editingIndex)
      ) {
        return { valid: false, error: "This sentence is already in the removal list" };
      }
      return { valid: true };
    },
    [sentences, state.editingIndex],
  );

  const handleSave = useCallback(
    (sentence: string) => {
      const trimmed = sentence.trim();
      const validation = validateSentence(trimmed);
      if (!validation.valid) {
        showAlert("Invalid Sentence", validation.error || "Please enter a valid sentence");
        return false;
      }

      const list = [...sentences];
      if (state.editingIndex !== null) {
        list[state.editingIndex] = trimmed;
      } else {
        list.unshift(trimmed);
      }

      void saveSentences(list);
      closeDialog();
      return true;
    },
    [sentences, validateSentence, saveSentences, state.editingIndex, closeDialog, showAlert],
  );

  const handleDelete = useCallback(
    (index: number) => {
      showAlert(
        "Remove Sentence",
        "Are you sure you want to remove this sentence from the blocklist?",
        [
          { text: "Cancel", style: "cancel" },
          {
            text: "Delete",
            style: "destructive",
            onPress: () => {
              const list = sentences.filter((_, i) => i !== index);
              void saveSentences(list);
            },
          },
        ],
      );
    },
    [sentences, saveSentences, showAlert],
  );

  return {
    ...state,
    openDialog,
    closeDialog,
    updateSentence,
    handleSave,
    handleDelete,
    isEditing: state.editingIndex !== null,
  };
}
