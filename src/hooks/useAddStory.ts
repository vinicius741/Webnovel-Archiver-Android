import { useState } from "react";
import { useRouter } from "expo-router";
import * as Clipboard from "expo-clipboard";
import { useAppAlert } from "../context/AlertContext";
import { storageService } from "../services/StorageService";
import {
  buildStoryForAdd,
  EmptyChapterListError,
  prepareStorySyncData,
  UnsupportedSourceError,
} from "../services/story/storySyncOrchestrator";

export const useAddStory = () => {
  const router = useRouter();
  const { showAlert } = useAppAlert();
  const [url, setUrl] = useState("");
  const [loading, setLoading] = useState(false);
  const [statusMessage, setStatusMessage] = useState("");

  const handlePaste = async () => {
    const text = await Clipboard.getStringAsync();
    if (text) {
      setUrl(text);
    }
  };

  const handleAdd = async (tabId?: string) => {
    if (!url) return;
    setLoading(true);
    setStatusMessage("Initializing...");
    try {
      console.log("[AddStory] Processing:", url);
      const prepared = await prepareStorySyncData({
        sourceUrl: url,
        loadExistingStory: (storyId) => storageService.getStory(storyId),
        onStatus: setStatusMessage,
        onProgress: setStatusMessage,
      });

      setStatusMessage("Saving story...");
      const story = buildStoryForAdd(prepared, tabId);

      await storageService.addStory(story);
      setLoading(false);
      setStatusMessage("");
      showAlert("Success", `Added "${prepared.metadata.title}" to library.`, [
        { text: "OK", onPress: () => router.back() },
      ]);
    } catch (e) {
      if (e instanceof UnsupportedSourceError) {
        showAlert("Error", "Unsupported source URL.");
      } else if (e instanceof EmptyChapterListError) {
        showAlert("Error", "No chapters found. Please check the URL.");
      } else {
        console.error(e);
        showAlert("Error", "Failed to fetch the novel. " + (e as Error).message);
      }
      setLoading(false);
      setStatusMessage("");
    }
  };

  return {
    url,
    setUrl,
    loading,
    statusMessage,
    handlePaste,
    handleAdd,
  };
};
