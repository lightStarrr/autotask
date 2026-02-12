package android.window;

import android.os.Parcel;
import android.os.Parcelable;

public class TaskSnapshot implements Parcelable {

    protected TaskSnapshot(Parcel in) {
    }

    public static final Creator<TaskSnapshot> CREATOR = new Creator<TaskSnapshot>() {
        @Override
        public TaskSnapshot createFromParcel(Parcel in) {
            return new TaskSnapshot(in);
        }

        @Override
        public TaskSnapshot[] newArray(int size) {
            return new TaskSnapshot[size];
        }
    };

    @Override
    public int describeContents() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        throw new RuntimeException("Stub!");
    }
}
