import { storageService } from "../services/StorageService";
import { useAppAlert } from "../context/AlertContext";
import { Story, Chapter } from "../types";
import { validateStory, validateChapter } from "../utils/storyValidation";

interface UseStoryActionsParams {
  story: Story | null;
  onStoryUpdated: (updatedStory: Story) => void;
  onStoryDeleted?: () => void;
}

export const useStoryActions = ({
  story,
  onStoryUpdated,
  onStoryDeleted,
}: UseStoryActionsParams) => {
  const { showAlert } = useAppAlert();

  const deleteStory = () => {
    if (!validateStory(story)) return;

    showAlert(
      "Delete Novel",
      `Are you sure you want to delete "${story.title}"? This action cannot be undone.`,
      [
        {
          text: "Cancel",
          style: "cancel",
        },
        {
          text: "Delete",
          style: "destructive",
          onPress: async () => {
            await storageService.deleteStory(story.id);
            onStoryDeleted?.();
          },
        },
      ],
    );
  };

  const markChapterAsRead = async (chapter: Chapter) => {
    if (!validateStory(story)) return;
    if (!validateChapter(story, chapter)) return;

    const newLastReadId =
      story.lastReadChapterId === chapter.id ? undefined : chapter.id;

    // Update epubConfig.rangeStart if startAfterBookmark is enabled and bookmark changes
    let updatedEpubConfig = story.epubConfig;
    if (newLastReadId && story.epubConfig?.startAfterBookmark) {
      const bookmarkIndex = story.chapters.findIndex((ch) => ch.id === newLastReadId);
      if (bookmarkIndex !== -1) {
        updatedEpubConfig = {
          ...story.epubConfig,
          rangeStart: bookmarkIndex + 2, // Start after bookmark (1-based, +1 for next chapter)
        };
      }
    }

    const updatedStory = {
      ...story,
      lastReadChapterId: newLastReadId,
      epubConfig: updatedEpubConfig,
    };

    try {
      if (newLastReadId) {
        await storageService.updateLastRead(story.id, newLastReadId);
        onStoryUpdated(updatedStory);
        const cleanTitle = chapter.title.replace(/\n/g, " ").trim();
        showAlert(
          "Marked as Read",
          `Marked "${cleanTitle}" as your last read location.`,
        );
      } else {
        await storageService.addStory(updatedStory);
        onStoryUpdated(updatedStory);
        showAlert("Cleared", "Reading progress cleared.");
      }
    } catch (error) {
      console.error("Failed to update last read", error);
    }
  };

  return {
    deleteStory,
    markChapterAsRead,
  };
};
