import { site } from "../../lib/content";

export function Wordmark() {
    return (
        <span className="wordmark" aria-label={site.brand}>
            HHGOA<span className="wordmark__dot">.</span>
        </span>
    );
}
