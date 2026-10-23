import "@testing-library/jest-dom/vitest";
import {afterEach, vi} from "vitest";
import {cleanup} from "@testing-library/react";

afterEach(
    cleanup
)

// jsdom has no viewport layout or native modal implementation. These shims
// exercise application event handling, not browser focus trapping or CSS.
Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: vi.fn((media: string) => ({
        media, matches: false, onchange: null,
        addEventListener: vi.fn(), removeEventListener: vi.fn(),
        addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: vi.fn(),
    })),
});

HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
    this.querySelector<HTMLElement>("[autofocus], button, a[href]")?.focus();
};
HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
    this.dispatchEvent(new Event("close"));
};
