package com.animarvle.app.ui.profile

data class AnimeAvatar(
    val id: String,
    val name: String,
    val drawableRes: Int? = null,
    val imageUrl: String? = null,
)

object AvatarPresets {
    // 39 anime avatar slots matching the reference spec ("39 available")
    // When the user provides custom avatars, they can be registered here.
    val avatars: List<AnimeAvatar> = listOf(
        AnimeAvatar("1", "Goku SSB", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b246-K5U328KowzUf.png"),
        AnimeAvatar("2", "Goku Super Saiyan", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b246-gP3yJ3yL2Xg9.png"),
        AnimeAvatar("3", "Naruto Uzumaki", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b17-70hLrkp9O4b8.png"),
        AnimeAvatar("4", "Vegeta", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b813-w1bLSm6y3R6v.png"),
        AnimeAvatar("5", "Vegeta SSB", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b813-wG4r9Q5z1K4j.png"),
        AnimeAvatar("6", "Kakashi Hatake", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b85-dY0G4K4W4P6g.png"),
        AnimeAvatar("7", "Satoru Gojo", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b127503-fE7VfV1uYQy9.jpg"),
        AnimeAvatar("8", "Kakashi Anbu", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b85-9bX0N2z1k3J8.png"),
        AnimeAvatar("9", "Saitama", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b73935-7XpG5lP2m8uN.png"),
        AnimeAvatar("10", "Marin Kitagawa", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b139046-rOQ8g2Kk4W1x.jpg"),
        AnimeAvatar("11", "Monkey D. Luffy", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b40-YkU1X2q4K6L0.png"),
        AnimeAvatar("12", "Roronoa Zoro", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b62-p8X6j7K0r4N9.png"),
        AnimeAvatar("13", "Tanjiro Kamado", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b126071-G2h6j9K1X4z0.jpg"),
        AnimeAvatar("14", "Nezuko Kamado", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b127518-g8h9K1x4Z3L0.jpg"),
        AnimeAvatar("15", "Zenitsu Agatsuma", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b129133-K2j4L8x9Q0Z1.jpg"),
        AnimeAvatar("16", "Inosuke Hashibira", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b129134-r4X7k1Z9Q2L0.jpg"),
        AnimeAvatar("17", "Levi Ackerman", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b45627-7Q1j6K4Z9L0x.jpg"),
        AnimeAvatar("18", "Eren Yeager", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b40882-9X2j4K7L1Q0z.jpg"),
        AnimeAvatar("19", "Mikasa Ackerman", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b40881-2L7j4K9X1Q0z.jpg"),
        AnimeAvatar("20", "Megumi Fushiguro", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b127502-8Q1j4K7L2X0z.jpg"),
        AnimeAvatar("21", "Nobara Kugisaki", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b127505-1X2j4K7L9Q0z.jpg"),
        AnimeAvatar("22", "Yuji Itadori", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b127500-4K7j2L9X1Q0z.jpg"),
        AnimeAvatar("23", "Sukuna", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b132085-7L1j4K2X9Q0z.jpg"),
        AnimeAvatar("24", "Killua Zoldyck", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b27-4K7j1L9X2Q0z.png"),
        AnimeAvatar("25", "Gon Freecss", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b30-1L9j4K7X2Q0z.png"),
        AnimeAvatar("26", "Kurapika", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b29-2X0j4K7L1Q0z.png"),
        AnimeAvatar("27", "Hisoka Morow", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b31-9Q1j4K7L2X0z.png"),
        AnimeAvatar("28", "Edward Elric", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b11-7X2j4K1L9Q0z.png"),
        AnimeAvatar("29", "Roy Mustang", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b68-1Q9j4K7L2X0z.png"),
        AnimeAvatar("30", "Gintoki Sakata", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b672-4L1j7K2X9Q0z.png"),
        AnimeAvatar("31", "Ken Kaneki", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b87275-2Q9j4K7L1X0z.png"),
        AnimeAvatar("32", "L Lawliet", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b71-9X1j4K7L2Q0z.png"),
        AnimeAvatar("33", "Light Yagami", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b80-1Q7j4K2L9X0z.png"),
        AnimeAvatar("34", "Spike Spiegel", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b1-7K1j4L9X2Q0z.png"),
        AnimeAvatar("35", "Ichigo Kurosaki", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b5-2L9j4K7X1Q0z.png"),
        AnimeAvatar("36", "Sasuke Uchiha", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b13-9X2j4K1L7Q0z.png"),
        AnimeAvatar("37", "Itachi Uchiha", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b14-1Q1j4K7L2X0z.png"),
        AnimeAvatar("38", "Guts", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b422-7L9j4K1X2Q0z.png"),
        AnimeAvatar("39", "Shoto Todoroki", imageUrl = "https://s4.anilist.co/file/anilistcdn/character/large/b85790-2X1j4K7L9Q0z.png"),
    )
}
