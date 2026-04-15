import { MD3Theme } from "react-native-paper";
import { JobStatus } from "../../services/download/types";

export const getStatusColor = (status: JobStatus, theme: MD3Theme): string => {
  switch (status) {
    case "pending":
      return theme.colors.secondary;
    case "downloading":
      return theme.colors.primary;
    case "paused":
      return theme.colors.tertiary;
    case "completed":
      return theme.colors.secondary;
    case "failed":
      return theme.colors.error;
    default:
      return theme.colors.secondary;
  }
};

export const getStatusLabel = (status: JobStatus): string => {
  switch (status) {
    case "pending":
      return "Queued";
    case "downloading":
      return "Downloading";
    case "paused":
      return "Paused";
    case "completed":
      return "Done";
    case "failed":
      return "Failed";
    default:
      return status;
  }
};
