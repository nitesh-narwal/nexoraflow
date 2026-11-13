package me.niteshh.nexoraflow.generator.catalog;

import java.util.List;
import java.util.Map;

/** Curated, fictional merchant pools and description templates per expense category. */
public final class ExpenseCatalog {

    private ExpenseCatalog() {
    }

    public static final Map<String, List<String>> MERCHANTS = Map.of(
            "MEALS", List.of("Cafe Roma", "Noodle Bar", "Green Fork", "Spice Route", "Burger Barn", "Sakura Kitchen", "Olive & Thyme",
                    "The Daily Bowl", "Taco Lane", "Urban Tandoor", "Bread & Butter", "Pho Corner", "Harvest Table", "Dim Sum House"),
            "TRAVEL", List.of("Skyway Air", "Aero Nimbus", "BlueJet", "Meridian Airways", "Cobalt Rail", "Transit Metro", "CityCab Co.",
                    "RideNow", "Express Coaches", "Orbit Airlines"),
            "LODGING", List.of("Harbor View Hotel", "Grand Meridian", "Parkside Inn", "Lotus Suites", "Alpine Lodge", "Riverside Residency",
                    "Metro Business Hotel", "Palm Court Hotel"),
            "OFFICE_SUPPLIES", List.of("OfficeWorld", "PaperTrail Supplies", "TechMart", "Ergo Desk Co.", "PrintPoint", "Gadget Garage",
                    "StationeryHub"),
            "TRAINING", List.of("Summit Learning Academy", "CloudCert Institute", "Pinnacle Training", "DataCraft Workshops", "Leadership Lab",
                    "DevSkills Online", "Agile Guild"),
            "CLIENT_ENTERTAINMENT", List.of("Bistro Nova", "The Capital Grill", "Skyline Lounge", "Harbour Grill", "Ember & Oak", "Jade Pavilion",
                    "Carlton Steakhouse", "Azure Rooftop"));

    public static final Map<String, List<String>> DESCRIPTIONS = Map.of(
            "MEALS", List.of("Working lunch", "Team lunch", "Dinner while travelling", "Breakfast meeting", "Late-night delivery during release"),
            "TRAVEL", List.of("Flight to customer site", "Airport transfer", "Train ticket", "Taxi to client office", "Return flight", "Metro pass"),
            "LODGING", List.of("Hotel night for onsite visit", "Conference accommodation", "Extended stay during project go-live"),
            "OFFICE_SUPPLIES", List.of("Notebooks and pens", "Monitor cable", "Ergonomic keyboard", "Whiteboard markers", "USB hub"),
            "TRAINING", List.of("Certification course fee", "Workshop registration", "Online learning subscription", "Conference pass"),
            "CLIENT_ENTERTAINMENT", List.of("Client dinner", "Customer appreciation lunch", "Prospect meeting dinner", "Partner networking event"));

    public static final List<String> RECEIPT_TYPES = List.of("PDF", "PDF", "JPG", "PNG", "JPEG");
    public static final List<String> RECEIPT_SOURCES = List.of("mobile-app", "email-forward", "scanner", "web-upload");
}
