import { storageService } from "../services/StorageService";
import { Story } from "../types";

export const saveAndNotify = async (
  story: Story,
  onStoryUpdated: (updatedStory: Story) => void,
): Promise<void> => {
  await storageService.addStory(story);
  onStoryUpdated(story);
};
