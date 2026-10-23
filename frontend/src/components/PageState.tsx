import type { ReactNode } from "react";
import Icon from "./Icon";

type PageStateProps = {
    variant: "loading" | "empty" | "error";
    title: string;
    children?: ReactNode;
    action?: ReactNode;
};

export default function PageState({ variant, title, children, action }: PageStateProps) {
    return (
        <section className={`page-state page-state-${variant}`} role={variant === "error" ? "alert" : variant === "loading" ? "status" : undefined}>
            <span className="state-icon"><Icon name="organisation" /></span>
            <h1>{title}</h1>
            {children && <p>{children}</p>}
            {action && <div className="state-actions">{action}</div>}
        </section>
    );
}
