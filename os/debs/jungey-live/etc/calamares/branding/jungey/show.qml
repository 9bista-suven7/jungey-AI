/* Shown while Jungey OS is being copied to the disk. */
import QtQuick 2.0;
import calamares.slideshow 1.0;

Presentation
{
    id: presentation

    Timer {
        interval: 12000
        running: presentation.activatedInCalamares
        repeat: true
        onTriggered: presentation.goToNextSlide()
    }

    Rectangle {
        anchors.fill: parent
        color: "#0b1622"
        z: -1
    }

    Slide {
        Image {
            id: logo1
            source: "welcome.png"
            width: 200; height: 200
            fillMode: Image.PreserveAspectFit
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: 30
        }
        Text {
            anchors.top: logo1.bottom
            anchors.topMargin: 24
            anchors.horizontalCenter: parent.horizontalCenter
            width: parent.width * 0.8
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.WordWrap
            color: "#d8f3ff"
            font.pixelSize: 18
            text: "<b>Welcome to Jungey OS.</b><br/><br/>A light Xfce desktop on Ubuntu 24.04 LTS, "
                + "with five years of security updates. It is being copied to your disk now."
        }
    }

    Slide {
        Text {
            anchors.centerIn: parent
            width: parent.width * 0.8
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.WordWrap
            color: "#d8f3ff"
            font.pixelSize: 18
            text: "<b>Meet Jungey, your assistant.</b><br/><br/>Open it from the menu or press "
                + "Super+J. Ask \"what time is it\", \"weather\", \"set a timer for 10 minutes\", "
                + "\"volume up\" or \"read my screen\". Everyday questions are answered on your "
                + "own machine."
        }
    }

    Slide {
        Text {
            anchors.centerIn: parent
            width: parent.width * 0.8
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.WordWrap
            color: "#d8f3ff"
            font.pixelSize: 18
            text: "<b>Getting software.</b><br/><br/>Synaptic Package Manager, in the System "
                + "menu, installs anything from the Ubuntu archive. From a terminal, "
                + "<tt>sudo apt install</tt> does the same."
        }
    }
}
