import fetchMock from 'jest-fetch-mock';

fetchMock.enableMocks();

jest.mock('@react-native-async-storage/async-storage', () => ({
    getItem: jest.fn(),
    setItem: jest.fn(),
    removeItem: jest.fn(),
    mergeItem: jest.fn(),
    clear: jest.fn(),
    getAllKeys: jest.fn(),
    multiGet: jest.fn(),
    multiSet: jest.fn(),
    multiRemove: jest.fn(),
    multiMerge: jest.fn(),
}));

jest.mock('expo-router', () => ({
    useFocusEffect: jest.fn((callback) => {
        const { useEffect } = require('react');
        useEffect(callback, []);
    }),
    router: {
        back: jest.fn(),
        push: jest.fn(),
        replace: jest.fn(),
    },
}));
