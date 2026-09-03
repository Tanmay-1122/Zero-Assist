import { footer, site } from "../../lib/content";
import { Container } from "../ui/Container";
import { Wordmark } from "../ui/Wordmark";

export function Footer() {
    return (
        <footer className="footer">
            <Container>
                <div className="footer__grid">
                    <div className="footer__brand">
                        <Wordmark />
                        <p>{footer.blurb}</p>
                    </div>

                    {footer.columns.map((col) => (
                        <div className="footer__col" key={col.heading}>
                            <h3>{col.heading}</h3>
                            <ul>
                                {col.links.map((link) => (
                                    <li key={link.label}>
                                        <a
                                            href={link.href}
                                            target={
                                                link.href.startsWith("http") ? "_blank" : undefined
                                            }
                                            rel={
                                                link.href.startsWith("http")
                                                    ? "noreferrer"
                                                    : undefined
                                            }
                                        >
                                            {link.label}
                                        </a>
                                    </li>
                                ))}
                            </ul>
                        </div>
                    ))}
                </div>

                <div className="footer__bottom">
                    <span>{footer.bottomLeft}</span>
                    <span>{footer.bottomRight}</span>
                    <a className="footer__backtop" href="#top">
                        Back to top ↑
                    </a>
                </div>
                <span className="sr-only">{site.brand} — on-device intelligence</span>
            </Container>
        </footer>
    );
}
