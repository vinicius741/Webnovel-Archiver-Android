import React from "react";
import { useLocalSearchParams } from "expo-router";

import { ReaderScreenContainer } from "../../../src/components/reader/ReaderScreenContainer";
import { ErrorBoundary } from "../../../src/components/common/ErrorBoundary";

export default function ReaderScreen() {
  const { storyId, chapterId, autoplay, resumeSession } = useLocalSearchParams<{
    storyId: string;
    chapterId: string;
    autoplay?: string;
    resumeSession?: string;
  }>();

  return (
    <ErrorBoundary contextLabel="Reader">
      <ReaderScreenContainer
        storyId={storyId}
        chapterId={chapterId}
        autoplay={autoplay}
        resumeSession={resumeSession}
      />
    </ErrorBoundary>
  );
}
