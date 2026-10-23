import { Link } from "react-router";
import Icon from "./Icon";

export default function Brand({ onNavigate }: { onNavigate?: () => void }) {
    return (
        <Link to="/" className="brand" aria-label="OpsPilot home" title="OpsPilot" onClick={onNavigate}>
            <span className="brand-mark"><Icon name="activity" /></span>
            <span className="brand-name">OpsPilot</span>
        </Link>
    );
}
