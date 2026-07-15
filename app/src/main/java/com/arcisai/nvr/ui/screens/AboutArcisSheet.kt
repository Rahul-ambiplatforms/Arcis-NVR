package com.arcisai.nvr.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.ui.theme.AccentPurple
import com.arcisai.nvr.ui.theme.ArcisGreen
import com.arcisai.nvr.ui.theme.ArcisOrange

// Sky-blue accent for the Terms card. The NVR theme has no EventRegionExit
// token (that lives in the main ArcisAI app), so we define it locally to keep
// the About sheet visually identical to the production app.
private val AboutBlue = Color(0xFF1FADE6)

// ═══════════════════════════════════════════════════════════
// Reusable Legal Sheet wrapper
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            content()
        }
    }
}

// ═══════════════════════════════════════════════════════════
// About ArcisAI — single sheet with accordion sections
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutArcisAISheet(onDismiss: () -> Unit) {
    var expandedSection by remember { mutableStateOf(-1) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("About ArcisAI", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }

            Text("Version 1.0.0", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(4.dp))

            // ── Accordion Items ──
            AccordionItem(
                icon = Icons.Outlined.Info, iconBg = AccentPurple,
                title = "About Us", isExpanded = expandedSection == 0,
                onClick = { expandedSection = if (expandedSection == 0) -1 else 0 }
            ) { AboutUsContent() }

            AccordionItem(
                icon = Icons.Outlined.PrivacyTip, iconBg = ArcisGreen,
                title = "Privacy Policy", isExpanded = expandedSection == 1,
                onClick = { expandedSection = if (expandedSection == 1) -1 else 1 }
            ) { PrivacyPolicyContent() }

            AccordionItem(
                icon = Icons.Outlined.Description, iconBg = AboutBlue,
                title = "Terms of Service", isExpanded = expandedSection == 2,
                onClick = { expandedSection = if (expandedSection == 2) -1 else 2 }
            ) { TermsOfServiceContent() }

            AccordionItem(
                icon = Icons.Outlined.VerifiedUser, iconBg = ArcisOrange,
                title = "Warranty Service", isExpanded = expandedSection == 3,
                onClick = { expandedSection = if (expandedSection == 3) -1 else 3 }
            ) { WarrantyServiceContent() }

            AccordionItem(
                icon = Icons.Outlined.Shield, iconBg = AccentPurple,
                title = "Warranty Policies", isExpanded = expandedSection == 4,
                onClick = { expandedSection = if (expandedSection == 4) -1 else 4 }
            ) { WarrantyPolicyContent() }
        }
    }
}

@Composable
private fun AccordionItem(
    icon: ImageVector, iconBg: Color, title: String,
    isExpanded: Boolean, onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Icon(
                    if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    content()
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Content composables for each accordion section
// ═══════════════════════════════════════════════════════════

@Composable
private fun AboutUsContent() {
    Body("Today, the innovation of the cloud has changed the scenario for 24/7 surveillance and security monitoring. It does away with the need for expensive hardware, while the security/surveillance data is maintained in the cloud.")
    Body("ArcisAI is a globally innovative and leading smart cloud CCTV camera and security solutions provider.")
    SubBody("ArcisAI \u2013 Next Gen Full HD Smart Cloud Camera for Retail & Enterprise. No Cable | No DVR | AI | 60 Sec Setup | H.265 | H.264 | SD Card Support | Cloud Storage.")
    SectionHeading("Our Mission")
    Body("Our Client\u2019s success is our success, we are deeply committed to understand our client\u2019s business & to sharpen our focus on our core competencies, and our flexible business approach allows us to look at client\u2019s engagement from a different perception. Expanding the possibilities for our clients and them to achieve competitive advantages. We offer service with innovative product and technology solutions that we provide and this enables our clients to transform and perform through our services. VMukti provides its clients with enhanced and innovative technics that let their events and activities achieve superior results through a unique way of working. Our team relies on some of our best technics and products delivered, which aims to get the right technics of its service, and by working as one team to create and deliver the optimum solution for clients.")
    SectionHeading("Our Vision")
    Body("Our vision is to bring out a massive transformation in the lives of billion-plus people through incredible innovation in cloud video communication technology. ArcisAI aims to reach maximum people and is strongly committed to all the different areas like Banking & Finance, Insurance, Retail, Electioneering, Education, Media, and Government institution for monitoring and surveillance services.")
    Body("All our products are cloud-enabled and 4G/LTE/Wi-Fi compliant. Our vision has pushed us to achieve global leadership in our business. With highly scalable cloud infrastructure in more than 150000+ locations, which are simultaneously monitored through cloud infrastructure using Assured Cloud data center and we are also providing complete and End-to-End software solution for Smart cloud monitoring with Wi-Fi And 4G/LTE. Our ultimate vision is to always stay upgraded with all new technologies of safety and security purposes which at last is beneficial and easy for people only.")
}

@Composable
private fun PrivacyPolicyContent() {
    SubBody("LAST UPDATED: January 25th 2021")
    Body("ArcisAI (ArcisAI, \"We\" or \"Our\") has drafted this Privacy Policy to ensure that you can clearly understand our information practices as you use the ArcisAI Platform (the \"Sites\"). This Privacy Policy describes the types of information we collect, how we use the information, with whom we share it, and the choices you can make about our collection, use, and disclosure of your information. We also describe in this Privacy Policy the measures we take to protect the security of your Personal Information and how you can contact us about our privacy practices.")
    Body("This Privacy Policy incorporates by reference the Terms of Use for the Sites, which apply to this Privacy Policy. When you visit the Sites or provide us with information, you consent to our use and disclosure of the information we collect or receive as described in this Privacy Policy and you agree to be bound by the terms and conditions of the policy.")
    Body("Please review this Privacy Policy periodically as we may update it from time to time to reflect changes in our data practices.")

    SectionHeading("THE INFORMATION WE COLLECT")
    Body("We may obtain information about you from various sources, including our Sites, when you call or email us or communicate with us through social media, or when you participate in an Event. An \"Event\" is an online gathering that includes registration before the event begins, activities between the event's start and end time, and after the event's end time. We also may obtain information about you from business partners and other third parties and publicly available information, including from our customers before or during an event. We may collect your information at different points in connection with our business and the Sites, including your registration, profile submissions, use of the Sites, interactions with us, and during an Event.")
    Body("The types of information we may obtain include:")
    Bullet("Personal Information, which is information that identifies you, such as your name, password, email address, address, phone number(s), and the photographic image.")
    Bullet("Demographic Information, such as zip code, country, years of work experience, skills, industry, certifications, degrees, etc. We do this to help you connect with other event participants like exhibitors.")
    Bullet("Preference Information, such as preferred time zone or whether to show a dialog on your next visit. We do this to personalize your experience on the Sites.")
    Bullet("Content Submissions, such as any information that you submit on our forms, blogs, social media pages, through file uploads, or via online chats as part of an Event.")
    Bullet("Anonymous information, such as pages visited, and time on the Sites. We do this to understand how the Sites are being used and what the engagement is like.")
    Body("We collect information stored in your social media profile that you authorize us to access when you use your social media profile to execute features on the Sites, such as the ability to log into the Sites using your social media profile credentials.")
    Body("In addition, when you visit our Sites, we may collect certain information by automated means, such as cookies and web beacons. The information we may collect by automated means includes, but is not limited to:")
    Bullet("Information about the devices our visitors use to access the Internet (such as the IP address and the device, browser, and operating system type).")
    Bullet("Pages and URLs that refer visitors to our Sites, also pages and URLs that visitors exit to once they leave our Sites.")
    Bullet("Dates and times of visits to our Sites.")
    Bullet("Information on actions taken on our Sites (such as page views, site navigation patterns, or application activity).")
    Bullet("A general geographic location (such as country and city) from which a visitor accesses our Sites.")
    Bullet("Search terms that visitors use to reach our Sites.")

    SectionHeading("HOW WE USE THE INFORMATION WE COLLECT")
    Body("We may use the information we obtain about you to:")
    Bullet("Create, manage and maintain your account on the Sites.")
    Bullet("Provide you with the Sites and other services that you request.")
    Bullet("Manage your participation in events hosted on the Sites, where you have signed up for such events and promotions.")
    Bullet("Maintain a record of the events in which you participate, including chat and webinar history and download activity.")
    Bullet("Enable you to interact with other event participants.")
    Bullet("Provide administrative notices or communications applicable to your use of the Sites.")
    Bullet("Respond to your questions and comments and provide customer support.")
    Bullet("Operate, evaluate and improve our business and the products and services we offer.")
    Bullet("Analyze and enhance our marketing communications and strategies (including by identifying when emails we have sent to you have been received and read).")
    Bullet("Analyze trends and statistics regarding visitors' use of our Sites, mobile applications and social media assets, and the jobs viewed or applied to on our Sites.")
    Bullet("Maintain the quality of the Sites, including detecting security incidents and protecting against malicious, deceptive, illegal or fraudulent activities.")
    Bullet("Notify you from time to time about relevant products and services operated by ArcisAI.")
    Bullet("Enforce our Sites' Terms of Use and legal rights.")
    Bullet("Comply with applicable legal requirements and industry standards and our policies.")
    Body("We also use non-personally identifiable information and certain technical information about your computer and your access to the Sites (including your internet protocol address) to operate, maintain and manage the Sites. We collect this information by automated means, such as cookies and web beacons.")
    Body("We may collect, compile, store, publish, promote, report, share or otherwise disclose or use all Aggregated Information; however, unless otherwise disclosed in this policy, we will not sell or otherwise transfer or disclose your Personal Information to a third party without your consent.")
    Body("If we seek to use the information we obtain about you in other ways, we will provide specific notice and request your consent at the time of collection.")

    SectionHeading("THE INFORMATION WE SHARE")
    Body("When you create an account on the Sites, ArcisAI will collect and retain information about you, some of which is Personal Information. You may be required to provide additional personal or demographic information when registering for an event hosted on the Sites including, but not limited to, photo, resume, work experience, educational qualification, location, skills, industry.")
    Body("The information you provide is collected by ArcisAI, and is shared with the company(ies) participating in the Event, which may be a customer or a Third Party. This includes personal information such as name, email address, resume and other questions you answer during the registration process. This also includes the conversation (chat) history from conversations with any other participant and/or organization.")
    Body("We may share your Personal Information with third party contractors or service providers to provide you with the services that we offer you through our Sites; to provide technical support, or to provide specific services in accordance with your instructions. These third parties are required not to use your Personal Information other than to provide the services requested by ArcisAI.")
    Body("We may also disclose specific user information when we determine, in good faith, that such disclosure is necessary to comply with the law, to cooperate with or seek assistance from law enforcement, to prevent a crime or protect national security, or to protect the interests or safety of ArcisAI, our customers, or other users of the Sites.")
    Body("You should be aware that any Personal Information you submit on the Sites may be read, collected, or used by other users of ArcisAI, and could be used to send you unsolicited messages. We are not responsible for the Personal Information you choose to submit to the Sites.")

    SectionHeading("HOW WE PROTECT PERSONAL INFORMATION")
    Body("ArcisAI maintains administrative, technical and physical safeguards designed to assist us in protecting the Personal Information we collect against accidental, unlawful or unauthorized destruction, loss, alteration, access, disclosure or use.")
    Body("Please note that no electronic transmission of information can be entirely secure. We cannot guarantee that the security measures we have in place to safeguard Personal Information will never be defeated or fail, or that those measures will always be sufficient or effective. Therefore, although we are committed to protecting your privacy, we do not promise, and you should not expect, that your Personal Information will always remain private. As a user of the Sites, you understand and agree that you assume all responsibility and risk for your use of the Sites, the internet generally, and the documents you post or access and for your conduct on and off the Sites. To further protect yourself, you should safeguard your ArcisAI Sites username and password and not share that information with anyone. You should also log off from and close your browser window when you have finished your visit to our Sites. Please note that we will never ask for your ArcisAI account password via email.")
    Body("ArcisAI does not safeguard your Personal Information from our customers when they are acting as the controller of your Personal Information, including when held by our customers outside of the Sites. They are responsible for handling Personal Information in accordance with their privacy policies.")

    SectionHeading("HOW TO UPDATE YOUR PERSONAL INFORMATION")
    Body("You may access, update and amend Personal Information included in your online account at any time by logging into your account and making the necessary changes.")
    Body("You may choose to deactivate your account at any time by emailing us at info@adiance.com. When we deactivate your account, you will be logged out of ArcisAI and you will no longer be able to log into ArcisAI to view the events you attended or the chats you had with other participants. Certain information in connection with your account may be retained after deactivation by ArcisAI or by the participants who it has already been shared according to this privacy policy.")

    SectionHeading("HOW TO DELETE YOUR ACCOUNT DATA")
    Body("All personal information you provide is securely stored and encrypted to protect your privacy. However, if you wish to delete all your account data, you can email us at info@adiance.com. Once we receive your request, we will take the necessary steps to delete your data from our system in compliance with our data retention policies.")

    SectionHeading("ABOUT COOKIES, TRACKING CHOICES, AND THIRD PARTY SERVICE PROVIDERS")
    Body("A \"cookie\" is a text file that websites send to a visitor's computer or other Internet-connected devices to uniquely identify the visitor's browser or to store information or settings in the browser. A \"web beacon\" is also called a Web bug or a pixel tag or a clear GIF. Used in combination with cookies, a web beacon is an often transparent graphic image, usually no larger than 1-pixel x 1-pixel, that is placed on a Web site or in an e-mail that is used to monitor the behavior of the user visiting the Website or sending the e-mail.")
    Body("ArcisAI uses cookies and other similar technologies for the convenience of our users. Cookies enable us to serve secure pages to our users without asking them to sign in repeatedly. ArcisAI also uses cookies to store non-personally identifying information such as your preferences and to ensure the proper functioning and efficiency of our Sites.")
    Body("Most Internet browsers enable you to erase cookies from your computer hard drive, block all cookies, or receive a warning before a cookie is stored. Please be aware, that our Sites cannot be used without cookies enabled. Our Sites also do not respond to web browser \"do not track\" requests.")
    Body("ArcisAI permits third-party cookies on its Sites. Third-party services located on the Sites, including those that allow for single sign-on, commenting, live chat and social media sharing, may use cookies to remember user preference settings and interaction history.")
    Body("The third-party vendors, including Google, whose services we use will place cookies on web browsers to serve ads based on past visits to our website. This allows us to make special offers and continue to market our services to those who have shown interest in our service.")
    Body("The companies that provide third-party tools and services operate under their own privacy policies and ArcisAI encourages you to be aware of the privacy policies of such companies. ArcisAI does not have control over or access to any information contained in the cookies that are set on your computer by third-party tool providers.")

    SectionHeading("INTERNATIONAL TRANSFER OF YOUR INFORMATION")
    Body("The Sites are hosted in the United States and any personal information that we collect from you is currently stored in the United States. If you are accessing the Sites outside of the U.S., you consent to the transfer of your personal information to the United States when you register. Please be advised that United States law may not offer the same privacy protections as the law in your jurisdiction.")

    SectionHeading("LINKS TO OTHER SITES")
    Body("Content on our Sites may contain links to other sites that are not owned or controlled by ArcisAI. Please be aware that we are not responsible for the privacy practices of such other sites. We encourage you to be aware when you leave our Sites and to read the privacy statements of every website that collects Personal Information. This Privacy Policy applies only to information collected on ArcisAI.")

    SectionHeading("DISPUTES")
    Body("If you believe that we have not adhered to the Privacy Policy, please contact us by e-mail at privacy@adiance.com. We will do our best to address your concerns. If you feel that your complaint has been addressed incompletely, we invite you to let us know for further investigations.")

    SectionHeading("CHILDREN'S PRIVACY")
    Body("Children under the age of 13 (or other age as required by local law) are permitted to use the site ONLY as part of an approved agreement with a customer providing for children's data. Children under 13 years old are otherwise prohibited from using the site. If you are a parent or guardian and you are aware that your child has provided us with personal data that is not part of an approved customer relationship, please contact us immediately. If we learn that we have collected any personal data in violation of applicable law, we will promptly take steps to delete such information and terminate the child's account.")
    Body("For such customer accounts, ArcisAI involves the collection and maintenance of personal data about children under 13 through the site. It is operated by: Ambiplatforms LLC, 312W. 2nd St#1064 Casper WY 82601. Please contact ArcisAI at info@adiance.com with any questions about the collection, use and sharing of children's data.")
    Body("The information collected about children under 13, as well as other personal data of students, includes name and email address. This information is or may be used for: account creation, user verification, delivery of the products and services, share content between users, user interaction, customer support, user communication, prevent fraud, detect security incidents, analytics, respond to legal inquiries or terminate accounts.")
    Body("No child or student personal data is made available to the public by ArcisAI. The parent of a child under 13 can review or have deleted the personal data held by ArcisAI and refuse to permit its further collection or use by notifying ArcisAI via info@adiance.com.")

    SectionHeading("YOUR PRIVACY RIGHTS")
    Body("Depending on your location, you may have the right to exercise certain privacy rights under applicable laws, including the right of erasure, the right to object to processing, the right to restrict processing, and the right to access / data portability. To exercise any of the above rights, please contact us at the email address listed below. To comply with your request, we may have to verify your identity.")
    Body("You may also have the right to make a complaint to the relevant authorities in your jurisdiction. If you need further assistance regarding your rights, please contact us at the email address listed below and we will consider your request by applicable law.")

    SectionHeading("GENERAL DATA PROTECTION REGULATION (GDPR)")
    Body("ArcisAI will comply with all applicable data protection and privacy laws & regulations in the performance of its obligations under the General Data Protection Regulation (\"GDPR\", from the GDPR implementation date) or, until GDPR implementation date, the Data Protection Act 1998 (\"Data Protection Laws\").")

    SectionHeading("PRIVACY POLICY UPDATES")
    Body("If we decide to make material changes to our Privacy Policy, we will notify you by prominently posting notice of the changes on the Site and updating the date at the top of the Privacy Policy. Therefore, we encourage you to check the date of our Privacy Policy whenever you visit the website for any updates or changes.")
    Body("We understand that changes to this Privacy Policy may affect your decision to use our Sites. You have the option to deactivate your account for any reason. Continued use of our Sites and their services following notice of such changes shall indicate your acknowledgment of such changes and agreement to be bound by the terms and conditions of such changes.")

    SectionHeading("HOW TO CONTACT US")
    Body("If you have any questions or comments about this Privacy Policy or our use of your personal information, or to exercise your rights, please contact us at info@adiance.com.")
}

@Composable
private fun TermsOfServiceContent() {
    SubBody("LAST UPDATED: January 25th 2021")
    SubBody("These terms apply to all ArcisAI products.")

    SectionHeading("1. Your Relationship with ArcisAI")
    Body("Your use of ArcisAI products, software, services, and websites (referred to collectively as the \"Services\" in this document) is subject to the terms of a legal agreement between you and ArcisAI. This document explains how the agreement is made up and sets out some of the terms of that agreement.")
    Body("Unless otherwise agreed in writing with ArcisAI, your agreement with ArcisAI will always include, at a minimum, the terms and conditions set out in this document.")

    SectionHeading("2. Accepting the Terms")
    Body("In order to use the Services, you must first agree to the Terms. You may not use the Services if you do not accept the Terms.")
    Bullet("A) Clicking to accept or agree to the Terms, where this option is made available to you by ArcisAI in the user interface for any Service.")
    Bullet("B) By actually using the Services. In this case, you understand and agree that ArcisAI will treat your use of the Services as acceptance of the Terms from that point onwards.")

    SectionHeading("3. Language of the Terms")
    Body("Where ArcisAI has provided you with a translation of the English language version of the Terms, then you agree that the translation is provided for your convenience only and that the English language versions of the Terms will govern your relationship with ArcisAI.")
    Body("If there is any contradiction between what the English language version of the Terms says and what a translation says, then the English language version shall take precedence.")

    SectionHeading("4. Provision of the Services by ArcisAI")
    Body("ArcisAI is constantly innovating in order to provide the best possible experience for its users. You acknowledge and agree that the form and nature of the Services which ArcisAI provides may change from time to time without prior notice to you.")
    Body("As part of this continuing innovation, you acknowledge and agree that ArcisAI may stop (permanently or temporarily) providing the Services (or any features within the Services) to you or to users generally at ArcisAI's sole discretion, without prior notice to you. You may stop using the Services at any time. You do not need to specifically inform ArcisAI when you stop using the Services.")

    SectionHeading("5. Use of the Services by You")
    Body("You agree to use the Services only for purposes that are permitted by (a) the Terms and (b) any applicable law, regulation, or generally accepted practices or guidelines in the relevant jurisdictions (including any laws regarding the export of data or software to and from the United States or other relevant countries).")
    Body("You agree that you will not engage in any activity that interferes with or disrupts the Services (or the servers and networks which are connected to the Services).")
    Body("Unless you have been specifically permitted to do so in a separate agreement with ArcisAI, you agree that you will not reproduce, duplicate, copy, sell, trade, or resell the Services for any purpose.")
    Body("You agree that you are solely responsible for (and that ArcisAI has no responsibility to you or to any third party for) any breach of your obligations under the Terms and for the consequences (including any loss or damage which ArcisAI may suffer) of any such breach.")
    Body("You agree that you cannot impersonate any real or fictional person or entity or perform any fraudulent activity.")
    Body("You must be at least 13 years old to use the Services.")
    Body("Upon signing up for the Services, you agree to receive email communications from ArcisAI, which is important for ArcisAI to deliver the Services to you.")

    SectionHeading("6. Privacy and Your Personal Information")
    Body("For information about ArcisAI's data protection practices, please read ArcisAI's privacy policy.")
    Body("You agree to the use of your data in accordance with ArcisAI's privacy policies.")

    SectionHeading("7. Content in the Services")
    Body("You understand that all information (such as data files, written text, computer software, music, audio files or other sounds, photographs, videos or other images) which you may have access to as part of, or through your use of, the Services are the sole responsibility of the person from which such content originated. All such information is referred to below as the \"Content\".")
    Body("Prohibited Content: You agree that you will not send, display, post, submit, publish or transmit Content that: (i) is unfair or deceptive under the consumer protection laws of any jurisdiction; (ii) is copyrighted, protected by trade secret or otherwise subject to third party proprietary rights, including privacy and publicity rights, unless you are the owner of such rights; (iii) creates a risk to a person's safety or health, creates a risk to public safety or health, compromises national security, or interferes with an investigation by law enforcement; (iv) impersonates another person; (v) promotes illegal drugs, violates export control laws, relates to illegal gambling, or illegal arms trafficking; (vi) is unlawful, defamatory, libelous, threatening, pornographic, harassing, hateful, racially or ethnically offensive, or encourages conduct that would be considered a criminal offense, gives rise to civil liability, violates any law, or is otherwise dishonest, inaccurate, inappropriate, malicious or fraudulent; (vii) involves theft or terrorism; (viii) constitutes an unauthorized commercial communication; (ix) contains the contact information or any personally identifiable information of any third party unless you have first obtained the express consent of said third party; and/or (x) breaches this agreement.")
    Body("ArcisAI reserves the right (but shall have no obligation) to pre-screen, review, flag, filter, modify, refuse or remove any or all Content from any Service without further notice to you. We have complete discretion whether to publish your Content and have the right to delete any and all Content at any time which we believe to be in violation of the \"Prohibited Content\".")
    Body("You should be aware that Content presented to you as part of the Services, including but not limited to advertisements in the Services and sponsored Content within the Services may be protected by intellectual property rights which are owned by the sponsors or advertisers who provide that Content to ArcisAI. You may not modify, rent, lease, loan, sell, distribute or create derivative works based on this Content unless you have been specifically told that you may do so by ArcisAI or by the owners of that Content, in a separate agreement.")
    Body("You understand that by using the Services you may be exposed to Content that you may find offensive, indecent or objectionable and that, in this respect, you use the Services at your own risk.")
    Body("You agree that you are solely responsible for (and that ArcisAI has no responsibility to you or to any third party for) any Content that you create, transmit or display while using the Services and for the consequences of your actions by doing so.")

    SectionHeading("8. Other Content")
    Body("The Services may include hyperlinks to other web sites or content or resources. ArcisAI may have no control over any web sites or resources which are provided by companies or persons other than ArcisAI.")
    Body("You acknowledge and agree that ArcisAI is not responsible for the availability of any such external sites or resources, and does not endorse any advertising, products or other materials on or available from such web sites or resources.")
    Body("You acknowledge and agree that ArcisAI is not liable for any loss or damage which may be incurred by you as a result of the availability of those external sites or resources, or as a result of any reliance placed by you on the completeness, accuracy or existence of any advertising, products or other materials on, or available from, such web sites or resources.")

    SectionHeading("9. Proprietary Rights")
    Body("You acknowledge and agree that ArcisAI owns all legal right, title and interest in and to the Services, including any intellectual property rights which subsist in the Services (whether those rights happen to be registered or not, and wherever in the world those rights may exist).")
    Body("Unless you have agreed otherwise in writing with ArcisAI, nothing in the Terms gives you a right to use any of ArcisAI's trade names, trademarks, service marks, logos, domain names, and other distinctive brand features.")
    Body("If you have been given an explicit right to use any of these brand features in a separate written agreement with ArcisAI, then you agree that your use of such features shall be in compliance with that agreement, any applicable provisions of the Terms, and ArcisAI's brand feature use guidelines as updated from time to time.")
    Body("ArcisAI acknowledges and agrees that it obtains no right, title or interest from you (or your licensors) under these Terms in or to any Content that you submit, post, transmit or display on, or through, the Services, including any intellectual property rights which subsist in that Content. Unless you have agreed otherwise in writing with ArcisAI, you agree that you are responsible for protecting and enforcing those rights and that ArcisAI has no obligation to do so on your behalf.")
    Body("You agree that you shall not remove, obscure, or alter any proprietary rights notices (including copyright and trademark notices) which may be affixed to or contained within the Services.")
    Body("Unless you have been expressly authorized to do so in writing by ArcisAI, you agree that in using the Services, you will not use any trademark, service mark, trade name, logo of any company or organization in a way that is likely or intended to cause confusion about the owner or authorized user of such marks, names or logos.")

    SectionHeading("10. License from ArcisAI")
    Body("ArcisAI gives you a personal, worldwide, royalty-free, non-assignable and non-exclusive license to use the software provided to you by ArcisAI as part of the Services (referred to as the \"Software\" below). This license is for the sole purpose of enabling you to use and enjoy the benefit of the Services as provided by ArcisAI, in the manner permitted by the Terms.")
    Body("Subject to section 1.2, you may not (and you may not permit anyone else to) copy, modify, create a derivative work of, reverse engineer, decompile or otherwise attempt to extract the source code of the Software or any part thereof, unless this is expressly permitted or required by law, or unless you have been specifically told that you may do so by ArcisAI, in writing.")
    Body("Subject to section 1.2, unless ArcisAI has given you specific written permission to do so, you may not assign (or grant a sub-license of) your rights to use the Software, grant a security interest in or over your rights to use the Software, or otherwise transfer any part of your rights to use the Software.")

    SectionHeading("11. Content License from You")
    Body("You retain copyright and any other rights you already hold in Content which you submit, post or display on or through, the Services.")

    SectionHeading("12. Software Updates")
    Body("The Software which you use may automatically download and install updates from time to time from ArcisAI. These updates are designed to improve, enhance and further develop the Services and may take the form of bug fixes, enhanced functions, new software modules and completely new versions. You agree to receive such updates (and permit ArcisAI to deliver these to you) as part of your use of the Services.")

    SectionHeading("13. Ending Your Relationship with ArcisAI")
    Body("The Terms will continue to apply until terminated by either you or ArcisAI as set out below.")
    Body("ArcisAI may at any time, terminate its legal agreement with you if (A) you have breached any provision of the Terms (or have acted in a manner which clearly shows that you do not intend to, or are unable to comply with the provisions of the Terms); or (B) ArcisAI is required to do so by law; or (C) the partner with whom ArcisAI offered the Services to you has terminated its relationship with ArcisAI or ceased to offer the Services to you; or (D) ArcisAI is transitioning to no longer providing the Services to users in the country in which you are resident or from which you use the service; or (E) the provision of the Services to you by ArcisAI is, in ArcisAI's opinion, no longer commercially viable.")
    Body("Nothing in this Section shall affect ArcisAI's rights regarding provision of Services under Section 4 of the Terms.")
    Body("When these Terms come to an end, all of the legal rights, obligations and liabilities that you and ArcisAI have benefited from, been subject to (or which have accrued over time whilst the Terms have been in force) or which are expressed to continue indefinitely, shall be unaffected by this cessation.")

    SectionHeading("14. Exclusion of Warranties")
    Body("Nothing in these terms, including sections 14 and 15, shall exclude or limit ArcisAI's warranty or liability for losses which may not be lawfully excluded or limited by applicable law. Some jurisdictions do not allow the exclusion of certain warranties or conditions or the limitation or exclusion of liability for loss or damage caused by negligence, breach of contract or breach of implied terms, or incidental or consequential damages. Accordingly, only the limitations which are lawful in your jurisdiction will apply to you and our liability will be limited to the maximum extent permitted by law.")
    Body("You expressly understand and agree that your use of the services is at your sole risk and that the services are provided \"as is\" and \"as available.\"")
    Body("In particular, ArcisAI, its subsidiaries and affiliates, and its licensors do not represent or warrant to you that: (a) your use of the services will meet your requirements, (b) your use of the services will be uninterrupted, timely, secure or free from error, (c) any information obtained by you as a result of your use of the services will be accurate or reliable, and (d) that defects in the operation or functionality of any software provided to you as part of the services will be corrected.")
    Body("Any material downloaded or otherwise obtained through the use of the services is done at your own discretion and risk and that you will be solely responsible for any damage to your computer system or other device or loss of data that results from the download of any such material.")
    Body("No advice or information, whether oral or written, obtained by you from ArcisAI or through or from the services shall create any warranty not expressly stated in the terms.")
    Body("ArcisAI further expressly disclaims all warranties and conditions of any kind, whether express or implied, including, but not limited to the implied warranties and conditions of merchantability, fitness for a particular purpose and non-infringement.")

    SectionHeading("15. Limitation of Liability")
    Body("Subject to the overall provision in paragraph 14.1 above, you expressly understand and agree that ArcisAI, its subsidiaries and affiliates, and its licensors shall not be liable to you for: (a) any direct, indirect, incidental, special consequential or exemplary damages which may be incurred by you, however caused and under any theory of liability. This shall include, but not be limited to, any loss of profit (whether incurred directly or indirectly), any loss of goodwill or business reputation, any loss of data suffered, cost of procurement of substitute goods or services, or other intangible loss; (b) any loss or damage which may be incurred by you, including but not limited to loss or damage as a result of: (i) any reliance placed by you on the completeness, accuracy or existence of any advertising, or as a result of any relationship or transaction between you and any advertiser or sponsor whose advertising appears on the services; (ii) any changes which ArcisAI may make to the services, or for any permanent or temporary cessation in the provision of the services; (iii) the deletion of, corruption of, or failure to store, any content and other communications data maintained or transmitted by or through your use of the services; (iv) your failure to provide ArcisAI with accurate account information; (v) your failure to keep your password or account details secure and confidential.")
    Body("The limitations on ArcisAI's liability to you in paragraph 14.1 above shall apply whether or not ArcisAI has been advised of or should have been aware of the possibility of any such losses arising.")
    Body("The total liability of ArcisAI to you for all damages, losses, and causes of action (whether in contract, tort (including negligence), or otherwise) shall not exceed the amount actually paid by you during a one-year period for the specific service giving rise to the liability.")

    SectionHeading("16. Indemnification")
    Body("You agree to defend, indemnify, and hold us harmless, including our subsidiaries, affiliates, and all of our respective officers, agents, partners, and employees, from and against any loss, damage, liability, claim, or demand, including reasonable attorneys' fees and expenses, made by any third party due to or arising out of: (1) your Contributions; (2) use of the Site; (3) breach of these Terms of Use; (4) any breach of your representations and warranties set forth in these Terms of Use; (5) your violation of the rights of a third party, including but not limited to intellectual property rights; or (6) any overt harmful act toward any other user of the Site with whom you connected via the Site. Notwithstanding the foregoing, we reserve the right, at your expense, to assume the exclusive defense and control of any matter for which you are required to indemnify us, and you agree to cooperate, at your expense, with our defense of such claims. We will use reasonable efforts to notify you of any such claim, action, or proceeding which is subject to this indemnification upon becoming aware of it.")

    SectionHeading("17. Copyright and Trademark Policies")
    Body("It is ArcisAI's policy to respond to notices of alleged copyright infringement that comply with applicable international intellectual property law (including, in the United States, the Digital Millennium Copyright Act) and to terminate the accounts of repeat infringers.")

    SectionHeading("18. Advertisements")
    Body("Some of the Services are supported by advertising revenue and may display advertisements and promotions. These advertisements may be targeted to the content of information stored on the Services, queries made through the Services, or other information.")
    Body("The manner, mode, and extent of advertising by ArcisAI on the Services are subject to change without specific notice to you.")
    Body("In consideration for ArcisAI granting you access to and use of the Services, you agree that ArcisAI may place such advertising on the Services.")

    SectionHeading("19. Taxes, Raffles, and Auctions")
    Body("If there are taxes, other governmental charges, or any other fees associated with your use of the Site including the auction, item sales, raffles, or other financial transactions on the Site, these will be your responsibility to pay. You should consult your tax adviser on any potential taxes or tax effects related to the auction, raffle, fund-a-need appeal, sales, and other transactions made through the Site.")
    Body("If you choose to include a raffle as part of your event, you agree that you understand and comply with all federal, state, and local regulations that apply to raffles and the sale of raffle tickets through our site. You further agree that you shall indemnify, defend, and hold ArcisAI, its subsidiaries, affiliates, officers, employees, directors, shareholders, predecessors, successors in interest, and other agents, harmless from and against any claim, demand, suit, cause of action, proceeding, loss, liability, damage, or expense (including reasonable attorney fees) arising out of or related to raffle activities.")
    Body("Once an auction has closed it cannot be re-opened. In order to re-open an auction, a new auction must be created and will incur a new activation fee.")

    SectionHeading("20. Changes to the Terms")
    Body("ArcisAI may make changes to the Terms from time to time.")
    Body("You understand and agree that if you use the Services after the date on which the Terms have changed, ArcisAI will treat your use as acceptance of the updated Universal Terms or Additional Terms.")

    SectionHeading("21. Dispute Resolution")
    Body("Arbitration — If any dispute, claim, or controversy (\"Claims\") arises under this Agreement or through your use of the Services, such dispute shall be resolved by binding arbitration in accordance with the Commercial Arbitration Rules of the American Arbitration Association (\"AAA\") then pertaining, except where such rules conflict with this section, in which case this section shall control. There shall be three arbitrators. The parties agree that one arbitrator shall be appointed by each party within twenty (20) days of receipt by respondent(s) of the Request for Arbitration or in default thereof appointed by the AAA in accordance with its Commercial Rules, and the third presiding arbitrator shall be appointed by agreement of the two party-appointed arbitrators within fourteen (14) days of the appointment of the second arbitrator or, in default of such agreement, by the AAA. Any court with jurisdiction shall enforce this section and enter judgment on any award. Within forty-five (45) days of initiation of arbitration, the parties shall reach agreement upon and thereafter follow procedures assuring that the arbitration will be concluded and the award rendered within no more than eight (8) months from selection of arbitrators. The arbitration shall be held in Natrona County, Wyoming, and the arbitrators shall apply the substantive law of the State of Wyoming, except that the interpretation and enforcement of this arbitration provision shall be governed by the Federal Arbitration Act.")
    Body("Exceptions — You and ArcisAI agree that the following Claims are not subject to the above provisions concerning negotiations and binding arbitration: (a) any Claim seeking to enforce or protect, or concerning the validity of, any of your or ArcisAI intellectual property rights; (b) any Claim related to, or arising from, allegations of theft, piracy, invasion of privacy, or unauthorized use; (c) any claim for equitable relief; and (d) any claim by a resident of the European Union or Switzerland regarding our adherence to the Privacy Shield Principles.")
    Body("Class action/jury trial waiver — With respect to all persons and entities, regardless of whether they have obtained or used the services for personal, commercial, or other purposes, all claims must be brought in the parties' individual capacity, and not as a plaintiff or class member in any purported class action, collective action, private attorney general action, or other representative proceeding. This waiver applies to class arbitration, and, unless we agree otherwise, the arbitrator may not consolidate more than one person's claims. You agree that, by entering into this agreement, you and we are each waiving the right to a trial by jury or to participate in a class action, collective action, private attorney general action, or other representative proceeding of any kind.")

    SectionHeading("22. Force Majeure")
    Body("ArcisAI will be excused from performance under this Agreement for any period of time that ArcisAI is prevented from performing its obligations hereunder as a result of an act of God, criminal acts, distributed denial of service attacks, any acts of the common enemy, the elements, earthquakes, floods, fires, epidemics, riots, war, utility or communication failures, or other causes beyond its reasonable control.")

    SectionHeading("23. Miscellaneous")
    Body("These Terms of Use and any policies or operating rules posted by us on the Site or in respect to the Site constitute the entire agreement and understanding between you and us. Our failure to exercise or enforce any right or provision of these Terms of Use shall not operate as a waiver of such right or provision. These Terms of Use operate to the fullest extent permissible by law. We may assign any or all of our rights and obligations to others at any time. We shall not be responsible or liable for any loss, damage, delay, or failure to act caused by any cause beyond our reasonable control. If any provision or part of a provision of these Terms of Use is determined to be unlawful, void, or unenforceable, that provision or part of the provision is deemed severable from these Terms of Use and does not affect the validity and enforceability of any remaining provisions. There is no joint venture, partnership, employment, or agency relationship created between you and us as a result of these Terms of Use or use of the Site. You agree that these Terms of Use will not be construed against us by virtue of having drafted them. You hereby waive any and all defenses you may have based on the electronic form of these Terms of Use and the lack of signing by the parties hereto to execute these Terms of Use.")
}

@Composable
private fun WarrantyServiceContent() {
    SubBody("LAST UPDATED: January 25th 2021")
    Body("Welcome to the online store (the \"Store\") provided by Ambiplatforms LLC (\"Ambiplatforms\"). Your purchase of ArcisAI hardware products (\"Products\") and/or subscription services (\"Subscription Services\") from the Store constitutes your agreement to be bound by these Terms & Conditions of Sale (\"Terms & Conditions\") and any additional terms we provide, including but not limited to our Terms of Service and the terms of the Limited Warranty included in-box with a Product.")
    Body("This is a legal agreement. By placing an order for ArcisAI products and/or subscription services, you are accepting and agreeing to these Terms & Conditions. You represent and warrant that you have the right, authority, and capacity to accept and agree to these Terms & Conditions. You represent that you are of sufficient legal age in your jurisdiction or residence to purchase and use products and to enter into this agreement. If you do not agree with any of the provisions of these Terms & Conditions, you should not purchase the products.")
    Body("We reserve the right to change these Terms & Conditions at any time, so please review the Terms & Conditions each time prior to making a purchase from the Store. Every time you order Products from ArcisAI, the Terms & Conditions in force at that time will apply between you and ArcisAI. If you purchase our Subscription Services, we will notify you in the event we make changes to these Terms & Conditions that affect your subscription.")
    Body("The Store is for retail sales to private consumers only. Please contact contact@adiance.com if you wish to purchase wholesale supplies.")
    Body("As a consumer, you have certain legal rights. The disclaimers, exclusions, and limitations of liability under these Terms & Conditions will not apply to the extent prohibited by applicable law. Some jurisdictions do not allow the exclusion of implied warranties, including exclusions relating to products or services that are faulty or not as described, or the exclusion or limitation of incidental or consequential damages or other rights.")
    Body("Although the Store is accessible worldwide, the Products and Subscription Services offered on the Store are not designed and tested for use in all countries. If you choose to access the Store and/or use the Products and Subscription Services outside India, you do so on your own initiative and you are solely responsible for complying with applicable local laws in your country. To the extent permissible by law, ArcisAI accepts no responsibility or liability for any damage or loss caused by your access or use of the Store, Products and Subscription Services in a non-Target Country.")

    SectionHeading("1. Compatibility")
    Body("You acknowledge that you have verified the compatibility of the Products you are purchasing with other equipment in your home. You are solely responsible for determining the compatibility of the Products with other equipment in your home, and you accept that lack of compatibility is not a valid claim under the warranty provided with your Products and does not otherwise constitute a basis for receiving a refund after the 30-day refund policy described below.")

    SectionHeading("2. Reservations and Pre-Orders")
    Body("Products available for reservation and pre-order are not offered for sale by ArcisAI. Your placement of a reservation and pre-order does not create a contract for sale.")
    Body("By placing a reservation and pre-order for a Product that is not yet available for sale, you make an offer to ArcisAI to purchase the Product subject to these Terms & Conditions. ArcisAI will obtain an authorization from your bank or credit card company for no charge. An authorization from your payment card company may stay open for several days or weeks before a charge is actually made.")
    Body("You may cancel your offer to purchase Products at any time prior to shipment and you will not be charged. You will receive an email several days prior to the shipment of reserved Products in which you will have an option to cancel your offer.")
    Body("Later, when the Product is offered for sale, ArcisAI may accept your offer to purchase Products subject to these Terms & Conditions. At that time, ArcisAI will capture payment on the payment card you provided and ship your Product.")
    Body("ArcisAI reserves the right to cancel or refuse any order for any reason at any time prior to shipment, including after an order has been submitted, whether or not the order has been confirmed.")

    SectionHeading("Payment")
    Body("By providing a credit card or other payment method accepted by ArcisAI, you represent and warrant that you are authorized to use the designated payment method and that you authorize us (or our third-party payment processor) to charge your payment method for the total amount of your order (including any applicable taxes and other charges). If the payment method you provide cannot be verified, is invalid or is otherwise not acceptable, your order may be suspended or cancelled.")

    SectionHeading("Subscription Services")
    Bullet("Subscription Plans: We offer different subscription plans for our Subscription Services. For more information, please visit https://www.ambicam.in/support.")
    Bullet("Continuous Subscriptions: When you purchase any of our Subscription Services, you expressly acknowledge and agree that (1) ArcisAI is authorized to charge you a monthly or annual subscription service fee depending on the billing cycle you choose for as long as your subscription continues, and (2) your subscription is continuous until you cancel it or such Subscription Service is suspended, discontinued, or terminated in accordance with ArcisAI's Terms of Service.")
    Bullet("Cancellations and Refunds: You may cancel your Subscription Services at any time by logging into your ArcisAI Account and selecting \"Cancel Subscription.\" Note that merely unpairing a Product from a Subscription Service will not trigger cancellation. In the event you cancel a Subscription Service, we will provide a prorated refund for the period of time starting the day after cancellation through the remainder of your billing cycle.")
    Bullet("Free Trials: We may offer free trials of our Subscription Services for limited periods of time. We have no obligation to notify you when your free trial ends, and we reserve the right to modify or terminate free trials at any time, without notice and in our sole discretion.")

    SectionHeading("Availability and Pricing")
    Body("All Products offered on the Store are subject to availability, and we reserve the right to impose quantity limits on any order, to reject all or part of an order and to discontinue offering certain Products and/or Subscription Services without prior notice. Prices for the Products and Subscription Services are subject to change at any time, but changes will not affect any order for Products you have already placed.")

    SectionHeading("Sales Tax")
    Body("Depending on the order, ArcisAI calculates and charges sales tax as prescribed in accordance with applicable laws in states, country.")

    SectionHeading("Resale and Title Transfer")
    Body("Purchases made on the Store are intended for end users only, and are not authorized for resale. Title for Products purchased from the Store passes to the purchaser at the time of delivery by ArcisAI to the freight carrier, but ArcisAI and/or the freight carrier will be responsible for any Product loss or damage that occurs when the Product is in transit to you.")

    SectionHeading("Shipping and Delivery")
    Body("Prices for the Products do not include shipping costs. Our delivery charges and methods are as described on the Store website from time to time. The estimated arrival or delivery date is not a guaranteed delivery date for your order. Refused deliveries will be returned to our warehouse. It may take up to 30 days for the returned items to be identified as refused and processed for a refund. The Products available on the Store have been designed, marketed and sold for use by residents of the Country of India. All safety warnings, information, instructions, packaging, in-box materials, mobile apps, and support services are provided only in English (U.S.). You are responsible for complying with all applicable laws and regulations of the country for which the Product is destined.")

    SectionHeading("Installation")
    Body("There may be laws in the jurisdiction that you install a particular Product applicable to where and how to install that Product. You should check that you are in compliance with all relevant laws in your jurisdiction. ArcisAI is not responsible for any injury or damage caused by self-installation. ArcisAI maintains a list of recommended installers of the Products on its website. These installers are not ArcisAI employees and are not affiliated with ArcisAI. ArcisAI is not responsible for any conduct of or liability associated with these installers.")

    SectionHeading("Returns")
    Body("If you want to return the Product you purchased from the Store for a refund, you must notify us no later than 30 days following the date of purchase (the \"Cancellation Period\"). To initiate a return, you must inform us of your decision within the Cancellation Period by contacting ArcisAI customer support and clearly stating your desire to return the Product. ArcisAI customer service will provide you with a Return Materials Authorization (\"RMA\") that must be included with your return shipment to ArcisAI so ArcisAI can identify your shipment and with a return address.")
    Body("You must return your Product (and any promotional merchandise supplied with the Product) with an RMA within the 14 days following the day on which you notify ArcisAI customer support that you desire to return your Product. The Product is not eligible for a return after the 30-day period.")

    SectionHeading("Disputes and Arbitration")
    Bullet("Contact ArcisAI First: If a dispute arises between you and ArcisAI, our goal is to learn about and address your concerns. You agree that you will notify ArcisAI about any dispute you have with ArcisAI regarding these Terms & Conditions by contacting ArcisAI.")
    Bullet("Binding Arbitration: You and ArcisAI agree to submit any claim, dispute, action, cause of action, issue, or request for relief arising out of or relating to these Terms & Conditions or your use of the Products and/or Subscription Services to binding arbitration rather than by filing any lawsuit in any forum other than set forth in this section. You also waive your right to any form of appeal, review, or recourse to any court or other judicial authority, insofar as such waiver may be validly made.")
    Bullet("Arbitration Procedures: You must first present any claim or dispute to ArcisAI by contacting us to allow us an opportunity to resolve the dispute. You may request arbitration if your claim or dispute cannot be resolved within 60 days. The arbitration of any dispute or claim shall be conducted in accordance with the then current and applicable rules of the Indian Arbitration laws. The place of any arbitration will be Ahmedabad, Gujarat, India, and will be conducted in the English language. Claims will be heard by a single arbitrator.")
    Bullet("No Class Actions: There shall be no right or authority for any claims subject to this arbitration section to be arbitrated on a class action or consolidated basis or on bases involving claims brought in a purported representative capacity on behalf of the general public.")
    Bullet("Fees and Expenses: All administrative fees and expenses of arbitration will be divided equally between you and ArcisAI. Each party will bear the expense of its own counsel, experts, witnesses, and preparation and presentation of evidence at the arbitration hearing.")
    Bullet("Time Limit for Claims: You must contact ArcisAI within one (1) year of the date of the occurrence of the event or facts giving rise to a dispute, or you waive the right to pursue any claim based upon such event, facts, or dispute.")
    Bullet("Protection of Confidentiality and Intellectual Property Rights: Notwithstanding the foregoing, ArcisAI may seek injunctive or other equitable relief to protect its confidential information and intellectual property rights or to prevent loss of data or damage to its servers in any court of competent jurisdiction.")

    SectionHeading("Warranties and Disclaimers")
    Body("As far as permitted by applicable law, the Store, and all content available on the Store, is provided on an \"as-is\" basis without warranties or conditions of any kind, either express or implied, including, without limitation, warranties of title or implied warranties of merchantability or fitness for a particular purpose. All products and services purchased through the Store are provided on an \"as-is\" basis unless otherwise noted in the Limited Warranty included with a Product.")
    Body("You use our Products and Subscription Services at your own discretion and risk. You will be solely responsible for (and ArcisAI disclaims) any and all loss, liability or damages resulting from your use of a Product and/or Subscription Service, including damage or loss to your HVAC system, plumbing, home, Product, other peripherals connected to the Product, computer, mobile device, and all other items and pets in your home. Unless explicitly promising a \"guarantee,\" ArcisAI does not guarantee or promise any specific level of energy savings or other monetary benefit from the use of a Product and/or Subscription Services.")
    Body("ArcisAI gives no warranty regarding the life of the batteries used in a Product. Actual battery life may vary depending on a number of factors, including the configuration and usage of a Product.")

    SectionHeading("Limitation of Liability")
    Body("Nothing in these Terms & Conditions and in particular within this \"Limitation of Liability\" section shall attempt to exclude or limit liability that cannot be excluded under applicable law.")
    Body("To the maximum extent permitted by applicable law, in no event will (a) ArcisAI be liable for any indirect, consequential, exemplary, special, or incidental damages, including any damages for lost data or lost profits, arising from or relating to the products, even if ArcisAI knew or should have known of the possibility of such damages, and (b) ArcisAI's total cumulative liability arising from or related to the products, whether in contract or tort or otherwise, exceed the fees actually paid by you to ArcisAI or ArcisAI's authorized reseller for the product at issue in the prior six (6) months (if any). This limitation is cumulative and will not be increased by the existence of more than one incident or claim. ArcisAI disclaims all liability of any kind of ArcisAI's licensors and suppliers.")

    SectionHeading("Data Protection")
    Body("By placing an order for Products and/or Subscription Services, you agree and understand that ArcisAI may store, share, process and use data collected from your order form or phone/fax/email order for the purposes of processing the order. ArcisAI may also share such data globally with its subsidiaries and affiliates. ArcisAI companies shall protect your information in accordance with the Website Privacy Policy. ArcisAI works with other companies that help ArcisAI provide Products to you, such as freight carriers and credit card processing companies, and ArcisAI may have to share certain information with these companies for this purpose.")

    SectionHeading("Electronic Communications")
    Body("You are communicating with ArcisAI electronically when you use the Store or send email to ArcisAI. You agree that all agreements, notices, disclosures and other communications that we provide to you electronically satisfy any legal requirement that such communications be in writing. When you order in the Store, we collect and store your email address. From that point forward, your email address is used to send you information about ArcisAI's products and services unless you opt-out of such emails using the opt-out link in the emails.")

    SectionHeading("Notifications")
    Body("ArcisAI may provide notifications to you as required by law or for marketing or other purposes via (at its option) email to the primary email associated with your ArcisAI account, hard copy, or posting of such notice on the ArcisAI website. ArcisAI is not responsible for any automatic filtering you or your network provider may apply to email notifications. ArcisAI recommends that you add @adiance.com URLs to your email address book to help ensure you receive email notifications from ArcisAI.")

    SectionHeading("Force Majeure")
    Body("We will not be liable or responsible for any failure to perform, or delay in performance of, any of our obligations under a contract that is caused by an act or event beyond our reasonable control, including without limitation acts of God, strikes, lock-outs or other industrial action by third parties, civil commotion, riot, terrorist attack, war, fire, explosion, storm, flood, earthquake, epidemic or other natural disaster, failure of public or private telecommunications networks or impossibility of the use of railways, shipping, aircraft, motor transport or other means of public or private transport.")

    SectionHeading("Severability")
    Body("If any part of these Terms & Conditions becomes illegal, invalid, unenforceable, or prohibited in any respect under any applicable law or regulation, such provision or part thereof will be deemed to not form part of the contract between us. The legality, validity or enforceability of the remainder of these Terms & Conditions will remain in full force and effect.")

    SectionHeading("Survivability")
    Body("The obligations in Sections 1 of the act will survive any expiration or termination of these Terms.")

    SectionHeading("Waiver")
    Body("Failure or delay by us to enforce any of these Terms & Conditions will not constitute a waiver of our rights against you and does not affect our right to require future performance thereof.")

    SectionHeading("Governing Law and Jurisdiction")
    Body("These Terms & Conditions are governed by the laws of Country of India without giving effect to any conflict of laws principles that may provide the application of the law of another jurisdiction. You agree to submit to the personal jurisdiction of the state and federal courts in or for Ahmedabad, Gujarat, India, for the purpose of litigating all such claims or disputes, unless such claim or dispute is required to be arbitrated as set forth in an above section.")
}

@Composable
private fun WarrantyPolicyContent() {
    SubBody("LAST UPDATED: January 25th 2021")
    Body("At Ambiplatforms LLC, we deeply value your trust in us and are committed to making your shopping experience as seamless and delightful as possible.")
    Body("We assure you that all products sold on ArcisAI are brand new and 100% genuine. If the product you receive is damaged, defective, or not as described, our Friendly Returns Policy is here to help.")

    SectionHeading("Replacement Guarantee")
    Bullet("Validity: 30 days from delivery")
    Bullet("Covers: Damaged, Defective, Not as Described")
    Bullet("Resolution: Replacement")
    Body("If your product meets the above criteria, you can request a replacement within 30 days of delivery at no additional cost.")

    SectionHeading("When Does the Guarantee Not Apply?")
    Bullet("Damages caused by misuse of the product or incidental damage due to malfunction.")
    Bullet("Products with tampered or missing serial numbers.")
    Bullet("Items returned without original packaging, freebies, or accessories.")
    Bullet("Damages or defects not covered under ArcisAI's warranty.")

    SectionHeading("Possible Resolutions")
    Body("If a replacement cannot be provided due to unavailability of stock, you will receive a full refund \u2014 no questions asked.")

    SectionHeading("Important Notes")
    Bullet("For replacements, you are responsible for the shipping costs associated with returning the product to us.")
    Bullet("Replacements are subject to stock availability.")
    Body("Your satisfaction is our top priority, and we are here to ensure your experience with ArcisAI is hassle-free. For any issues, feel free to reach out to our support team.")
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentPurple)
}

@Composable
private fun Body(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SubBody(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Text("\u2022  ", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

