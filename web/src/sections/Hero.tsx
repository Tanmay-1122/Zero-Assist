import { hero, site } from "../lib/content";
import { Button } from "../components/ui/Button";
import { Container } from "../components/ui/Container";
import { Kicker } from "../components/ui/Kicker";
import { AssetFrame } from "../components/media/AssetFrame";
import { Parallax } from "../components/motion/Parallax";

/** Procedural key art — the signature rings. Swap via hero-keyart asset. */
function KeyArt() {
    return (
        <div className="keyart" aria-hidden="true">
            <div className="keyart__ring keyart__ring--1" />
            <div className="keyart__ring keyart__ring--2" />
            <div className="keyart__ring keyart__ring--3" />
            <div className="keyart__ring keyart__ring--4" />
            <div className="keyart__arc" />
            <div className="keyart__core" />
        </div>
    );
}

export function Hero() {
    return (
        <section className="hero" id="top">
            {/* Cinematic background stack — layers are parallaxed, key art is swappable */}
            <div className="hero__bg" aria-hidden="true">
                <Parallax speed={0.04} className="hero__aurora" />
                <Parallax speed={-0.02} className="hero__grid-lines" />
                {/* TODO: Replace with final generated hero artwork (see ASSETS_NEEDED.md) */}
                <AssetFrame slot="hero-keyart" frame={false} className="hero__keyart">
                    <KeyArt />
                </AssetFrame>
            </div>

            <Container className="hero__inner">
                <Kicker className="hero__kicker">{site.tagline}</Kicker>

                <h1 className="hero__title">
                    {hero.titleBefore}
                    <br />
                    {hero.titleAfter} <em className="grad-text">{hero.titleAccent}</em>
                </h1>

                <p className="hero__sub">{hero.sub}</p>

                <div className="hero__actions">
                    <Button href="#download">{hero.primaryCta}</Button>
                    <Button variant="ghost" href={site.docsUrl}>
                        {hero.ghostCta}
                    </Button>
                </div>

                <ul className="hero__meta">
                    {hero.meta.map((item) => (
                        <li className="hero__meta-item" key={item}>
                            {item}
                        </li>
                    ))}
                </ul>
            </Container>

            <a className="hero__scroll" href="#manifesto" aria-label="Scroll to the manifesto">
                Scroll
                <span className="hero__scroll-line" aria-hidden="true" />
            </a>
        </section>
    );
}
