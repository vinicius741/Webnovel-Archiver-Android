import React from "react";
import { useLocalSearchParams } from "expo-router";

import { StoryDetailsScreenContainer } from "../../src/components/details/StoryDetailsScreenContainer";

export default function StoryDetailsScreen() {
  const { id } = useLocalSearchParams();

  return <StoryDetailsScreenContainer id={id} />;
}
