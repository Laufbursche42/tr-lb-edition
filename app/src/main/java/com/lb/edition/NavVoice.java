// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Spoken navigation phrases in the phone's language. The on-screen guidance stays English; only the
 * voice output is localized. Supported EU languages have a full phrase set - any other language falls
 * back to English. The pronunciation is the phone's own TTS voice for that language (Android manages
 * that voice download); if the voice is not installed, {@link TtsHelper} falls back to whatever voice
 * is present.
 *
 * <p>Phrase slots per language: 0 turn-left, 1 turn-right, 2 sharp-left, 3 sharp-right, 4 continue,
 * 5 far template ("%s" = the maneuver), 6 near template, 7 arrive.</p>
 */
final class NavVoice {

    private static final Map<String, String[]> T = new HashMap<>();
    static {
        T.put("en", new String[]{"Turn left", "Turn right", "Turn sharp left", "Turn sharp right", "Continue", "In 200 meters, %s", "%s now", "Arriving at your destination"});
        T.put("de", new String[]{"Links abbiegen", "Rechts abbiegen", "Scharf links abbiegen", "Scharf rechts abbiegen", "Weiter geradeaus", "In 200 Metern %s", "Jetzt %s", "Sie erreichen Ihr Ziel"});
        T.put("fr", new String[]{"Tournez à gauche", "Tournez à droite", "Tournez franchement à gauche", "Tournez franchement à droite", "Continuez tout droit", "Dans 200 mètres, %s", "%s maintenant", "Vous arrivez à destination"});
        T.put("es", new String[]{"Gire a la izquierda", "Gire a la derecha", "Gire bruscamente a la izquierda", "Gire bruscamente a la derecha", "Siga recto", "En 200 metros, %s", "%s ahora", "Está llegando a su destino"});
        T.put("it", new String[]{"Svolta a sinistra", "Svolta a destra", "Svolta secca a sinistra", "Svolta secca a destra", "Prosegui dritto", "Tra 200 metri, %s", "%s ora", "Stai arrivando a destinazione"});
        T.put("pt", new String[]{"Vire à esquerda", "Vire à direita", "Vire acentuadamente à esquerda", "Vire acentuadamente à direita", "Siga em frente", "Em 200 metros, %s", "%s agora", "A chegar ao destino"});
        T.put("nl", new String[]{"Sla linksaf", "Sla rechtsaf", "Sla scherp linksaf", "Sla scherp rechtsaf", "Ga rechtdoor", "Over 200 meter %s", "%s nu", "U nadert uw bestemming"});
        T.put("pl", new String[]{"Skręć w lewo", "Skręć w prawo", "Skręć ostro w lewo", "Skręć ostro w prawo", "Jedź prosto", "Za 200 metrów %s", "%s teraz", "Dojeżdżasz do celu"});
        T.put("sv", new String[]{"Sväng vänster", "Sväng höger", "Sväng skarpt vänster", "Sväng skarpt höger", "Fortsätt rakt fram", "Om 200 meter, %s", "%s nu", "Du är framme vid målet"});
        T.put("da", new String[]{"Drej til venstre", "Drej til højre", "Drej skarpt til venstre", "Drej skarpt til højre", "Fortsæt ligeud", "Om 200 meter, %s", "%s nu", "Du ankommer til din destination"});
        T.put("fi", new String[]{"Käänny vasemmalle", "Käänny oikealle", "Käänny jyrkästi vasemmalle", "Käänny jyrkästi oikealle", "Jatka suoraan", "200 metrin päästä %s", "%s nyt", "Saavut määränpäähän"});
        T.put("cs", new String[]{"Odbočte vlevo", "Odbočte vpravo", "Odbočte ostře vlevo", "Odbočte ostře vpravo", "Pokračujte rovně", "Za 200 metrů %s", "%s teď", "Blížíte se k cíli"});
        T.put("sk", new String[]{"Odbočte vľavo", "Odbočte vpravo", "Odbočte ostro vľavo", "Odbočte ostro vpravo", "Pokračujte rovno", "O 200 metrov %s", "%s teraz", "Blížite sa k cieľu"});
        T.put("ro", new String[]{"Virați la stânga", "Virați la dreapta", "Virați brusc la stânga", "Virați brusc la dreapta", "Continuați înainte", "În 200 de metri, %s", "%s acum", "Ați ajuns la destinație"});
        T.put("hu", new String[]{"Forduljon balra", "Forduljon jobbra", "Forduljon élesen balra", "Forduljon élesen jobbra", "Haladjon egyenesen", "200 méter múlva %s", "%s most", "Megérkezett a célhoz"});
        T.put("el", new String[]{"Στρίψτε αριστερά", "Στρίψτε δεξιά", "Στρίψτε απότομα αριστερά", "Στρίψτε απότομα δεξιά", "Συνεχίστε ευθεία", "Σε 200 μέτρα, %s", "%s τώρα", "Φτάνετε στον προορισμό σας"});
        T.put("hr", new String[]{"Skrenite lijevo", "Skrenite desno", "Skrenite oštro lijevo", "Skrenite oštro desno", "Nastavite ravno", "Za 200 metara %s", "%s sada", "Stižete na odredište"});
        T.put("sl", new String[]{"Zavijte levo", "Zavijte desno", "Zavijte ostro levo", "Zavijte ostro desno", "Nadaljujte naravnost", "Čez 200 metrov %s", "%s zdaj", "Prihajate na cilj"});
        T.put("bg", new String[]{"Завийте наляво", "Завийте надясно", "Завийте рязко наляво", "Завийте рязко надясно", "Продължете направо", "След 200 метра %s", "%s сега", "Пристигате в местоназначението"});
        String[] no = {"Sving til venstre", "Sving til høyre", "Sving skarpt til venstre", "Sving skarpt til høyre", "Fortsett rett frem", "Om 200 meter, %s", "%s nå", "Du er fremme ved målet"};
        T.put("no", no);   // Norwegian: getLanguage() returns "no" or "nb"
        T.put("nb", no);
    }

    private final String[] p;
    private final Locale locale;

    private NavVoice(String lang, String[] p) {
        this.p = p;
        this.locale = new Locale(lang);
    }

    /** Build the voice set for the phone's current language, falling back to English. */
    static NavVoice forDevice() {
        String lang = "en";
        try {
            String l = Locale.getDefault().getLanguage();
            if (l != null && T.containsKey(l)) lang = l;
        } catch (Throwable ignored) { }
        return new NavVoice(lang, T.get(lang));
    }

    /** The locale whose TTS voice should speak these phrases (matches the chosen text language). */
    Locale locale() {
        return locale;
    }

    /** Translate an English maneuver ("Turn left", "Turn sharp right", ...) to the spoken phrase. */
    String maneuver(String english) {
        if (english == null) return p[4];
        String s = english.toLowerCase(Locale.US);
        boolean sharp = s.contains("sharp");
        if (s.contains("left")) return sharp ? p[2] : p[0];
        if (s.contains("right")) return sharp ? p[3] : p[1];
        return p[4]; // continue / straight
    }

    /** "In 200 meters, <maneuver>" in the chosen language. */
    String far(String maneuverPhrase) {
        return String.format(p[5], maneuverPhrase);
    }

    /** "<maneuver> now" in the chosen language. */
    String near(String maneuverPhrase) {
        return String.format(p[6], maneuverPhrase);
    }

    /** "Arriving at your destination" in the chosen language. */
    String arrive() {
        return p[7];
    }
}
