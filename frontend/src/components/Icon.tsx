import type { ReactNode } from "react";

const paths = {
    activity: <path d="M3 12h4l3-8 4 16 3-8h4" />,
    dashboard: <><rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" /><rect x="3" y="14" width="7" height="7" rx="1.5" /><rect x="14" y="14" width="7" height="7" rx="1.5" /></>,
    services: <><rect x="3" y="3" width="18" height="7" rx="2" /><rect x="3" y="14" width="18" height="7" rx="2" /><path d="M7 6.5h.01M7 17.5h.01M11 6.5h6M11 17.5h6" /></>,
    menu: <path d="M4 6h16M4 12h16M4 18h16" />,
    close: <path d="m6 6 12 12M6 18 18 6" />,
    plus: <path d="M12 5v14M5 12h14" />,
    chevronDown: <path d="m6 9 6 6 6-6" />,
    chevronLeft: <path d="m14 6-6 6 6 6" />,
    chevronRight: <path d="m10 6 6 6-6 6" />,
    logout: <><path d="M9 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h4M10 12h11m-4-4 4 4-4 4" /></>,
    organisation: <><rect x="5" y="3" width="14" height="18" rx="2" /><path d="M9 7h1m4 0h1M9 11h1m4 0h1M10 21v-6h4v6" /></>,
} satisfies Record<string, ReactNode>;

export default function Icon({ name, className = "" }: { name: keyof typeof paths; className?: string }) {
    return (
        <svg className={`icon ${className}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            {paths[name]}
        </svg>
    );
}
