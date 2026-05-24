const parseBooleanFlag = (value: string | undefined, fallback: boolean) => {
  if (value === undefined) return fallback;

  const normalized = value.trim().toLowerCase();
  if (normalized === "1" || normalized === "true" || normalized === "yes") {
    return true;
  }
  if (normalized === "0" || normalized === "false" || normalized === "no") {
    return false;
  }
  return fallback;
};

export const TTS_RELIABILITY_V2 = parseBooleanFlag(
  process.env.EXPO_PUBLIC_TTS_RELIABILITY_V2,
  true,
);
