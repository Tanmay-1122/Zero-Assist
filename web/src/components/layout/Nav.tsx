import { useEffect, useState } from "react";
import { navLinks, site } from "../../lib/content";
import { cn } from "../../lib/cn";
import { Button } from "../ui/Button";
import { Container } from "../ui/Container";
import { Wordmark } from "../ui/Wordmark";

export function Nav() {
    const [scrolled, setScrolled] = useState(false);
    const [open, setOpen] = useState(false);

    useEffect(() => {
        const onScroll = () => setScrolled(window.scrollY > 24);
        onScroll();
        window.addEventListener("scroll", onScroll, { passive: true });
        return () => window.removeEventListener("scroll", onScroll);
    }, []);

    // Lock body scroll while the overlay menu is open
    useEffect(() => {
        document.body.style.overflow = open ? "hidden" : "";
        return () => {
            document.body.style.overflow = "";
        };
    }, [open]);

    const close = () => setOpen(false);

    return (
        <>
            <header className={cn("nav", scrolled && "nav--scrolled")}>
                <Container className="nav__inner">
                    <a
                        href="#top"
                        className="nav__brand"
                        onClick={close}
                        aria-label={`${site.brand} — back to top`}
                    >
                        <Wordmark />
                    </a>

                    <nav className="nav__links" aria-label="Primary">
                        {navLinks.map((link) => (
                            <a key={link.href} className="nav__link" href={link.href}>
                                {link.label}
                            </a>
                        ))}
                    </nav>

                    <div className="nav__actions">
                        <Button href="#download" size="sm" className="nav__cta">
                            Download
                        </Button>
                        <button
                            type="button"
                            className="nav__toggle"
                            aria-expanded={open}
                            aria-controls="menu-overlay"
                            aria-label={open ? "Close menu" : "Open menu"}
                            onClick={() => setOpen((v) => !v)}
                        >
                            <span className="nav__toggle-bars" aria-hidden="true">
                                <span />
                                <span />
                                <span />
                            </span>
                        </button>
                    </div>
                </Container>
            </header>

            {/* Mobile full-screen menu */}
            <div
                id="menu-overlay"
                className={cn("menu-overlay", open && "menu-overlay--open")}
                aria-hidden={!open}
            >
                <Button variant="ghost" size="sm" className="menu-overlay__close" onClick={close}>
                    Close
                </Button>
                <nav aria-label="Mobile">
                    {navLinks.map((link, i) => (
                        <a
                            key={link.href}
                            className="menu-overlay__link"
                            href={link.href}
                            onClick={close}
                        >
                            <span>0{i + 1}</span>
                            {link.label}
                        </a>
                    ))}
                    <a className="menu-overlay__link" href="#download" onClick={close}>
                        <span>06</span>
                        Download
                    </a>
                </nav>
                <div className="menu-overlay__footer">
                    <span>Open source · MIT</span>
                    <span>{site.tagline}</span>
                </div>
            </div>
        </>
    );
}
