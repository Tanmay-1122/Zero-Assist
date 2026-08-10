import { Grain } from "./components/ui/Grain";
import { Nav } from "./components/layout/Nav";
import { Footer } from "./components/layout/Footer";
import { Marquee } from "./components/ui/Marquee";
import { Hero } from "./sections/Hero";
import { Manifesto } from "./sections/Manifesto";
import { Capabilities } from "./sections/Capabilities";
import { Engine } from "./sections/Engine";
import { Privacy } from "./sections/Privacy";
import { Story } from "./sections/Story";
import { OpenSource } from "./sections/OpenSource";
import { CTA } from "./sections/CTA";
import { marqueeItems } from "./lib/content";

export default function App() {
    return (
        <>
            <Grain />
            <Nav />
            <main>
                <Hero />
                <Marquee items={marqueeItems} />
                <Manifesto />
                <Capabilities />
                <Engine />
                <Privacy />
                <Story />
                <OpenSource />
                <CTA />
            </main>
            <Footer />
        </>
    );
}
