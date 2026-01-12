/**
 * Helper function to find and press a button in an alert dialog
 * @param mockFn - The mocked showAlert function
 * @param alertTitle - The title of the alert to find
 * @param buttonText - The text of the button to find
 * @returns The onPress function of the button
 * @throws Error if alert or button is not found
 */
export const findAndPressButton = (
    mockFn: jest.Mock,
    alertTitle: string,
    buttonText: string
): (() => void) => {
    const alertCall = mockFn.mock.calls.find((call: unknown[]) => call[0] === alertTitle);
    if (!alertCall) {
        throw new Error(`No alert found with title "${alertTitle}"`);
    }
    const button = alertCall[2].find((b: { text: string }) => b.text === buttonText);
    if (!button) {
        throw new Error(`No button found with text "${buttonText}" in alert "${alertTitle}"`);
    }
    return button.onPress;
};

/**
 * Helper function to get the last alert call
 * @param mockFn - The mocked showAlert function
 * @returns The last call to the mock function
 */
export const getLastAlertCall = (mockFn: jest.Mock): unknown[] => {
    return mockFn.mock.calls.slice(-1)[0];
};
