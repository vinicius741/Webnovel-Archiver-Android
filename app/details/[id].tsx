import React from "react";
import { useLocalSearchParams } from "expo-router";

import { StoryDetailsScreenContainer } from "../../src/components/details/StoryDetailsScreenContainer";
import { ErrorBoundary } from "../../src/components/common/ErrorBoundary";

export default function StoryDetailsScreen() {
  const { id } = useLocalSearchParams();

  return (
    <ErrorBoundary contextLabel="Story Details">
      <StoryDetailsScreenContainer id={id} />
    </ErrorBoundary>
  );
}
